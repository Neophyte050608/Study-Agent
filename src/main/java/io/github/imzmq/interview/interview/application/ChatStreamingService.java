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
        String sanitizedContent = inputSanitizer.sanitize(content);
        String traceId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        String taskId = taskManager.newTaskId();
        StreamEventEmitter streamEmitter = new ObservableStreamEmitter(new InterviewSseEmitterSender(emitter));
        taskManager.register(taskId, streamEmitter);
        taskManager.bindLifecycle(taskId, emitter);

        streamEmitter.emit(InterviewStreamEventType.META.value(), Map.of(
                "streamTaskId", taskId,
                "action", "chat",
                "traceId", traceId,
                "sessionId", sessionId,
                "retrievalModeRequested", retrievalMode == null ? "" : retrievalMode.name()
        ));

        streamingExecutor.execute(() -> runChat(sessionId, userId, sanitizedContent, retrievalMode, traceId, taskId, streamEmitter));
        return emitter;
    }

    public boolean stopTask(String streamTaskId) {
        String messageId = taskManager.getMessageId(streamTaskId);
        boolean stopped = taskManager.cancel(streamTaskId, "已停止生成");
        if (stopped) {
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

            webChatService.saveUserMessage(sessionId, content);
            assistantMessageId = chatStreamingSupport.createRunningAssistantPlaceholder(sessionId, traceId, taskId).getMessageId();
            taskManager.attachMessage(taskId, assistantMessageId);
            emitter.emit(InterviewStreamEventType.PROGRESS.value(), Map.of(
                    "stage", "THINKING", "label", "正在思考", "status", "running", "percent", 20));

            if (taskManager.isCancelled(taskId)) {
                terminalState = "CANCELLED";
                return;
            }

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

            if (chatScenarioHandlerRegistry.handle(chatContext)) {
                terminalState = taskManager.isCancelled(taskId) ? "CANCELLED" : "COMPLETED";
                return;
            }

            // 先走意图路由，判断是否为面试/编码等结构化意图
            Map<String, Object> payload = new HashMap<>();
            payload.put("query", content);
            Map<String, Object> context = new HashMap<>();
            context.put("sessionId", sessionId);
            context.put("userId", userId);
            context.put("history", intentRoutingHistory == null ? "" : intentRoutingHistory);
            context.put("traceId", traceId);
            context.put("executionMode", ExecutionMode.STREAM_ROUTE_ONLY.name());
            if (retrievalMode != null) {
                context.put("retrievalMode", retrievalMode.name());
            }

            TaskRequest request = new TaskRequest(null, payload, context);
            TaskResponse response = webChatService.getTaskRouterAgent().dispatch(request);
            chatContext.routeResponse(response);
            chatContext.routeSource(String.valueOf(context.getOrDefault("routeSource", "")));

            if (taskManager.isCancelled(taskId)) {
                terminalState = "CANCELLED";
                return;
            }
            if (chatScenarioHandlerRegistry.handle(chatContext)) {
                terminalState = taskManager.isCancelled(taskId) ? "CANCELLED" : "COMPLETED";
                return;
            }
        } catch (Exception ex) {
            log.warn("chat stream failed, taskId={}", taskId, ex);
            terminalState = "FAILED";
            terminalError = ex.getMessage() != null ? ex.getMessage() : "处理失败";
            if (!taskManager.isCancelled(taskId)) {
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













