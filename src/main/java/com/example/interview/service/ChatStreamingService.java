package com.example.interview.service;

import com.example.interview.agent.task.TaskRequest;
import com.example.interview.agent.task.TaskResponse;
import com.example.interview.agent.task.ExecutionMode;
import com.example.interview.core.RAGTraceContext;
import com.example.interview.security.InputSanitizer;
import com.example.interview.service.chatstream.ChatScenarioHandlerRegistry;
import com.example.interview.service.chatstream.ChatStreamingSupport;
import com.example.interview.service.chatstream.StreamingChatContext;
import com.example.interview.stream.InterviewSseEmitterSender;
import com.example.interview.stream.InterviewStreamEventType;
import com.example.interview.stream.InterviewStreamTaskManager;
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
    private final IntentRoutingContextBuilder intentRoutingContextBuilder;
    private final long timeoutMillis;

    public ChatStreamingService(
            WebChatService webChatService,
            InterviewStreamTaskManager taskManager,
            InputSanitizer inputSanitizer,
            ChatStreamingSupport chatStreamingSupport,
            ChatScenarioHandlerRegistry chatScenarioHandlerRegistry,
            IntentRoutingContextBuilder intentRoutingContextBuilder,
            @Qualifier("interviewStreamingExecutor") Executor streamingExecutor,
            @Value("${app.chat.streaming.timeout-millis:180000}") long timeoutMillis) {
        this.webChatService = webChatService;
        this.taskManager = taskManager;
        this.inputSanitizer = inputSanitizer;
        this.chatStreamingSupport = chatStreamingSupport;
        this.chatScenarioHandlerRegistry = chatScenarioHandlerRegistry;
        this.intentRoutingContextBuilder = intentRoutingContextBuilder;
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
        InterviewSseEmitterSender sender = new InterviewSseEmitterSender(emitter);
        taskManager.register(taskId, sender);
        taskManager.bindLifecycle(taskId, emitter);

        sender.sendEvent(InterviewStreamEventType.META.value(), Map.of(
                "streamTaskId", taskId,
                "action", "chat",
                "traceId", traceId,
                "sessionId", sessionId,
                "retrievalModeRequested", retrievalMode == null ? "" : retrievalMode.name()
        ));

        streamingExecutor.execute(() -> runChat(sessionId, userId, sanitizedContent, retrievalMode, traceId, taskId, sender));
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
                         String traceId, String taskId, InterviewSseEmitterSender sender) {
        RAGTraceContext.setTraceId(traceId);
        String assistantMessageId = "";
        try {
            if (taskManager.isCancelled(taskId)) return;

            webChatService.saveUserMessage(sessionId, content);
            assistantMessageId = chatStreamingSupport.createRunningAssistantPlaceholder(sessionId, traceId, taskId).getMessageId();
            taskManager.attachMessage(taskId, assistantMessageId);
            sender.sendEvent(InterviewStreamEventType.PROGRESS.value(), Map.of(
                    "stage", "THINKING", "label", "正在思考", "status", "running", "percent", 20));

            if (taskManager.isCancelled(taskId)) return;

            String history = webChatService.buildHistoryContext(sessionId);
            String intentRoutingHistory = webChatService.buildIntentRoutingContext(sessionId);
            String routingSummary = intentRoutingContextBuilder.build(sessionId, content, intentRoutingHistory);
            StreamingChatContext chatContext = new StreamingChatContext(
                    sessionId,
                    userId,
                    content,
                    retrievalMode,
                    traceId,
                    taskId,
                    sender
            );
            chatContext.assistantMessageId(assistantMessageId);
            chatContext.history(history);

            if (chatScenarioHandlerRegistry.handle(chatContext)) {
                return;
            }

            // 先走意图路由，判断是否为面试/编码等结构化意图
            Map<String, Object> payload = new HashMap<>();
            payload.put("query", content);
            Map<String, Object> context = new HashMap<>();
            context.put("sessionId", sessionId);
            context.put("userId", userId);
            context.put("history", routingSummary);
            context.put("traceId", traceId);
            context.put("executionMode", ExecutionMode.STREAM_ROUTE_ONLY.name());
            if (retrievalMode != null) {
                context.put("retrievalMode", retrievalMode.name());
            }

            TaskRequest request = new TaskRequest(null, payload, context);
            TaskResponse response = webChatService.getTaskRouterAgent().dispatch(request);
            chatContext.routeResponse(response);
            chatContext.routeSource(String.valueOf(context.getOrDefault("routeSource", "")));

            if (taskManager.isCancelled(taskId)) return;
            if (chatScenarioHandlerRegistry.handle(chatContext)) {
                return;
            }
        } catch (Exception ex) {
            log.warn("chat stream failed, taskId={}", taskId, ex);
            if (!taskManager.isCancelled(taskId)) {
                chatStreamingSupport.updateAssistantPlaceholder(
                        assistantMessageId,
                        "抱歉，处理您的请求时遇到了问题：" + (ex.getMessage() != null ? ex.getMessage() : "处理失败"),
                        chatStreamingSupport.buildTerminalMetadata(traceId, taskId, "FAILED", ex.getMessage()),
                        "text",
                        "FAILED"
                );
                sender.sendEvent(InterviewStreamEventType.ERROR.value(), Map.of(
                        "code", "CHAT_STREAM_FAILED",
                        "message", ex.getMessage() != null ? ex.getMessage() : "处理失败"));
                sender.sendEvent(InterviewStreamEventType.DONE.value(), "[DONE]");
            }
            chatStreamingSupport.completeTask(taskId, sender);
        } finally {
            RAGTraceContext.clear();
        }
    }

}
