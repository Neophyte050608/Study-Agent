package io.github.imzmq.interview.interview.application;
import io.github.imzmq.interview.knowledge.domain.KnowledgeRetrievalMode;

import io.github.imzmq.interview.agent.task.TaskRequest;
import io.github.imzmq.interview.agent.task.TaskResponse;
import io.github.imzmq.interview.agent.task.ExecutionMode;
import io.github.imzmq.interview.core.trace.RAGTraceContext;
import io.github.imzmq.interview.security.core.InputSanitizer;
import io.github.imzmq.interview.knowledge.application.observability.TraceNodeDefinition;
import io.github.imzmq.interview.knowledge.application.observability.TraceNodeDefinitions;
import io.github.imzmq.interview.knowledge.application.observability.TraceNodeHandle;
import io.github.imzmq.interview.knowledge.application.observability.TraceService;
import io.github.imzmq.interview.knowledge.application.chatstream.ChatScenarioHandlerRegistry;
import io.github.imzmq.interview.knowledge.application.chatstream.ChatStreamingSupport;
import io.github.imzmq.interview.knowledge.application.chatstream.StreamingChatContext;
import io.github.imzmq.interview.interview.application.WebChatService;
import io.github.imzmq.interview.stream.runtime.ObservableStreamEmitter;
import io.github.imzmq.interview.stream.runtime.InterviewSseEmitterSender;
import io.github.imzmq.interview.stream.runtime.InterviewStreamEventType;
import io.github.imzmq.interview.stream.runtime.InterviewStreamTaskManager;
import io.github.imzmq.interview.stream.runtime.StreamEventEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

@Service
public class ChatStreamingService {
    private static final Logger log = LoggerFactory.getLogger(ChatStreamingService.class);

    private final WebChatService webChatService;
    private final InterviewStreamTaskManager taskManager;
    private final InputSanitizer inputSanitizer;
    private final Executor streamingExecutor;
    private final ChatStreamingSupport chatStreamingSupport;
    private final ChatScenarioHandlerRegistry chatScenarioHandlerRegistry;
    private final TraceService traceService;
    private final long timeoutMillis;

    public ChatStreamingService(
            WebChatService webChatService,
            InterviewStreamTaskManager taskManager,
            InputSanitizer inputSanitizer,
            ChatStreamingSupport chatStreamingSupport,
            ChatScenarioHandlerRegistry chatScenarioHandlerRegistry,
            TraceService traceService,
            @Qualifier("interviewStreamingExecutor") Executor streamingExecutor,
            @Value("${app.chat.streaming.timeout-millis:180000}") long timeoutMillis) {
        this.webChatService = webChatService;
        this.taskManager = taskManager;
        this.inputSanitizer = inputSanitizer;
        this.chatStreamingSupport = chatStreamingSupport;
        this.chatScenarioHandlerRegistry = chatScenarioHandlerRegistry;
        this.traceService = traceService;
        this.streamingExecutor = streamingExecutor;
        this.timeoutMillis = timeoutMillis;
    }

    public SseEmitter streamChat(String sessionId, String userId, String content) {
        return streamChat(sessionId, userId, content, null);
    }

    public SseEmitter streamChat(String sessionId,
                                 String userId,
                                 String content,
                                 KnowledgeRetrievalMode retrievalMode) {
        // 先做输入清洗，降低提示注入/脏数据风险。
        String sanitizedContent = inputSanitizer.sanitize(content);
        // 每次流式请求独立 traceId，便于全链路观测。
        String traceId = UUID.randomUUID().toString();
        // SSE 连接对象，超时时间可配置。
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        // 为本次流创建 taskId，支持中断、状态查询、消息绑定。
        String taskId = taskManager.newTaskId();
        StreamEventEmitter streamEmitter = new ObservableStreamEmitter(new InterviewSseEmitterSender(emitter));
        taskManager.register(taskId, streamEmitter);
        taskManager.bindLifecycle(taskId, emitter);

        // 首帧 meta：前端拿到 streamTaskId 后可执行“停止生成”。
        streamEmitter.emit(InterviewStreamEventType.META.value(), Map.of(
                "streamTaskId", taskId,
                "action", "chat",
                "traceId", traceId,
                "sessionId", sessionId,
                "retrievalModeRequested", retrievalMode == null ? "" : retrievalMode.name()
        ));

        // 异步执行真正的聊天流程，避免阻塞请求线程。
        streamingExecutor.execute(() -> runChat(sessionId, userId, sanitizedContent, retrievalMode, traceId, taskId, streamEmitter));
        return emitter;
    }

    public boolean stopTask(String streamTaskId) {
        String messageId = taskManager.getMessageId(streamTaskId);
        boolean stopped = taskManager.cancel(streamTaskId, "已停止生成");
        if (stopped) {
            // 停止成功后，把占位助手消息状态回写为 CANCELLED。
            chatStreamingSupport.updateAssistantPlaceholder(
                    messageId,
                    "已停止生成",
                    chatStreamingSupport.buildTerminalMetadata("", streamTaskId, "CANCELLED", null),
                    "text",
                    "CANCELLED"
            );
        }
        return stopped;
    }

    private void runChat(String sessionId, String userId, String content,
                         KnowledgeRetrievalMode retrievalMode,
                         String traceId, String taskId, StreamEventEmitter emitter) {
        // 将 traceId 绑定到当前线程上下文，供后续节点埋点复用。
        RAGTraceContext.setTraceId(traceId);
        String assistantMessageId = "";
        TraceNodeHandle rootTrace = null;
        String terminalState = "RUNNING";
        String terminalError = null;
        try {
            rootTrace = traceService.startRoot(
                    traceId,
                    TraceNodeDefinitions.CHAT_STREAM_ROOT,
                    Map.of(
                            "scene", "chat",
                            "sessionId", sessionId,
                            "taskId", taskId,
                            "userId", userId,
                            "retrievalMode", retrievalMode == null ? "" : retrievalMode.name(),
                            "status", "RUNNING"
                    )
            );
            if (taskManager.isCancelled(taskId)) {
                terminalState = "CANCELLED";
                return;
            }

            // 1) 先落用户消息。
            webChatService.saveUserMessage(sessionId, content);
            // 2) 再创建助手占位消息，后续把流式结果回填到该消息。
            assistantMessageId = chatStreamingSupport.createRunningAssistantPlaceholder(sessionId, traceId, taskId).getMessageId();
            taskManager.attachMessage(taskId, assistantMessageId);
            // 3) 通知前端进入思考阶段。
            emitter.emit(InterviewStreamEventType.PROGRESS.value(), Map.of(
                    "stage", "THINKING", "label", "正在思考", "status", "running", "percent", 20));

            if (taskManager.isCancelled(taskId)) {
                terminalState = "CANCELLED";
                return;
            }

            // 构建两类上下文：生成上下文 + 路由上下文。
            String history = webChatService.buildHistoryContext(sessionId);
            String intentRoutingHistory = webChatService.buildIntentRoutingContext(sessionId);
            StreamingChatContext chatContext = new StreamingChatContext(
                    sessionId,
                    userId,
                    content,
                    retrievalMode,
                    traceId,
                    taskId,
                    emitter
            );
            chatContext.assistantMessageId(assistantMessageId);
            chatContext.history(history);

            // 先尝试场景处理器（例如快速命中结构化场景），命中则直接返回。
            if (chatScenarioHandlerRegistry.handle(chatContext)) {
                terminalState = taskManager.isCancelled(taskId) ? "CANCELLED" : "COMPLETED";
                return;
            }

            // 先走意图路由，判断是否为面试/编码等结构化意图
            Map<String, Object> payload = new HashMap<>();
            // query 是路由器输入主字段。
            payload.put("query", content);
            Map<String, Object> context = new HashMap<>();
            context.put("sessionId", sessionId);
            context.put("userId", userId);
            context.put("history", intentRoutingHistory == null ? "" : intentRoutingHistory);
            context.put("traceId", traceId);
            // STREAM_ROUTE_ONLY: 当前阶段只做流式路由判定，不直接走最终问答生成。
            context.put("executionMode", ExecutionMode.STREAM_ROUTE_ONLY.name());
            if (retrievalMode != null) {
                context.put("retrievalMode", retrievalMode.name());
            }

            // 路由 agent 产出结构化响应（包含 route/source/payload 等）。
            TaskRequest request = new TaskRequest(null, payload, context);
            TaskResponse response = webChatService.getTaskRouterAgent().dispatch(request);
            chatContext.routeResponse(response);
            chatContext.routeSource(String.valueOf(context.getOrDefault("routeSource", "")));

            if (taskManager.isCancelled(taskId)) {
                terminalState = "CANCELLED";
                return;
            }
            // 二次交给场景处理器：基于路由结果选择具体处理分支（RAG、面试卡片、练习题等）。
            if (chatScenarioHandlerRegistry.handle(chatContext)) {
                terminalState = taskManager.isCancelled(taskId) ? "CANCELLED" : "COMPLETED";
                return;
            }
        } catch (Exception ex) {
            log.warn("chat stream failed, taskId={}", taskId, ex);
            terminalState = "FAILED";
            terminalError = ex.getMessage() != null ? ex.getMessage() : "处理失败";
            if (!taskManager.isCancelled(taskId)) {
                // 失败时将错误结果落库并通知前端 error 事件。
                chatStreamingSupport.updateAssistantPlaceholder(
                        assistantMessageId,
                        "抱歉，处理您的请求时遇到了问题：" + terminalError,
                        chatStreamingSupport.buildTerminalMetadata(traceId, taskId, "FAILED", terminalError),
                        "text",
                        "FAILED"
                );
                emitter.emit(InterviewStreamEventType.ERROR.value(), Map.of(
                        "code", "CHAT_STREAM_FAILED",
                        "message", terminalError));
                emitter.done();
            }
            // 收尾：注销任务、关闭 emitter。
            chatStreamingSupport.completeTask(taskId, emitter);
        } finally {
            completeChatTrace(rootTrace, terminalState, terminalError, sessionId, taskId, userId, retrievalMode);
            RAGTraceContext.clear();
        }
    }

    private void completeChatTrace(TraceNodeHandle rootTrace,
                                   String terminalState,
                                   String terminalError,
                                   String sessionId,
                                   String taskId,
                                   String userId,
                                   KnowledgeRetrievalMode retrievalMode) {
        if (rootTrace == null) {
            return;
        }
        Map<String, Object> result = Map.of(
                "scene", "chat",
                "sessionId", sessionId,
                "taskId", taskId,
                "userId", userId,
                "retrievalMode", retrievalMode == null ? "" : retrievalMode.name(),
                "status", terminalState
        );
        if ("FAILED".equals(terminalState)) {
            TraceNodeHandle errorNode = traceService.startChild(rootTrace.traceId(), rootTrace.nodeId(), TraceNodeDefinitions.ERROR, result);
            traceService.fail(errorNode, terminalError, result);
            traceService.fail(rootTrace, terminalError, result);
            return;
        }
        TraceNodeDefinition terminalDefinition = "CANCELLED".equals(terminalState)
                ? TraceNodeDefinitions.CANCEL
                : TraceNodeDefinitions.FINISH;
        TraceNodeHandle terminalNode = traceService.startChild(rootTrace.traceId(), rootTrace.nodeId(), terminalDefinition, result);
        traceService.success(terminalNode, result);
        traceService.success(rootTrace, result);
    }

}













