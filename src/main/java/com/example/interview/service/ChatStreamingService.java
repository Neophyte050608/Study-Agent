package com.example.interview.service;

import com.example.interview.agent.task.TaskRequest;
import com.example.interview.agent.task.TaskResponse;
import com.example.interview.core.RAGTraceContext;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

@Service
public class ChatStreamingService {
    private static final Logger log = LoggerFactory.getLogger(ChatStreamingService.class);

    private final WebChatService webChatService;
    private final InterviewStreamTaskManager taskManager;
    private final Executor streamingExecutor;
    private final long timeoutMillis;
    private final int messageChunkSize;

    public ChatStreamingService(
            WebChatService webChatService,
            InterviewStreamTaskManager taskManager,
            @Qualifier("interviewStreamingExecutor") Executor streamingExecutor,
            @Value("${app.chat.streaming.timeout-millis:180000}") long timeoutMillis,
            @Value("${app.chat.streaming.message-chunk-size:48}") int messageChunkSize) {
        this.webChatService = webChatService;
        this.taskManager = taskManager;
        this.streamingExecutor = streamingExecutor;
        this.timeoutMillis = timeoutMillis;
        this.messageChunkSize = Math.max(1, messageChunkSize);
    }

    public SseEmitter streamChat(String sessionId, String userId, String content) {
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
                "sessionId", sessionId
        ));

        streamingExecutor.execute(() -> runChat(sessionId, userId, content, traceId, taskId, sender));
        return emitter;
    }

    public boolean stopTask(String streamTaskId) {
        return taskManager.cancel(streamTaskId, "已停止生成");
    }

    private void runChat(String sessionId, String userId, String content,
                         String traceId, String taskId, InterviewSseEmitterSender sender) {
        RAGTraceContext.setTraceId(traceId);
        try {
            if (taskManager.isCancelled(taskId)) return;

            webChatService.saveUserMessage(sessionId, content);
            sender.sendEvent(InterviewStreamEventType.PROGRESS.value(), Map.of(
                    "stage", "THINKING", "label", "正在思考", "status", "running", "percent", 20));

            if (taskManager.isCancelled(taskId)) return;

            String history = webChatService.buildHistoryContext(sessionId);
            Map<String, Object> payload = new HashMap<>();
            payload.put("query", content);
            Map<String, Object> context = new HashMap<>();
            context.put("sessionId", sessionId);
            context.put("userId", userId);
            context.put("history", history);
            context.put("traceId", traceId);

            sender.sendEvent(InterviewStreamEventType.PROGRESS.value(), Map.of(
                    "stage", "DISPATCHING", "label", "正在检索知识", "status", "running", "percent", 50));

            TaskRequest request = new TaskRequest(null, payload, context);
            TaskResponse response = webChatService.getTaskRouterAgent().dispatch(request);

            if (taskManager.isCancelled(taskId)) return;

            String replyText = webChatService.extractReplyText(response);
            sender.sendEvent(InterviewStreamEventType.PROGRESS.value(), Map.of(
                    "stage", "GENERATING", "label", "正在生成回答", "status", "running", "percent", 80));
            sendChunked(sender, "answer", replyText);

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("traceId", traceId);
            if (response.data() instanceof Map<?, ?> dataMap && dataMap.containsKey("sources")) {
                metadata.put("sources", dataMap.get("sources"));
            }
            webChatService.saveAssistantMessage(sessionId, replyText, metadata);
            webChatService.autoTitleIfNeeded(sessionId, content);

            sender.sendEvent(InterviewStreamEventType.FINISH.value(), Map.of(
                    "action", "chat",
                    "result", Map.of("content", replyText, "traceId", traceId)
            ));
            sender.sendEvent(InterviewStreamEventType.DONE.value(), "[DONE]");
            taskManager.unregister(taskId);
            sender.complete();
        } catch (Exception ex) {
            log.warn("chat stream failed, taskId={}", taskId, ex);
            if (!taskManager.isCancelled(taskId)) {
                sender.sendEvent(InterviewStreamEventType.ERROR.value(), Map.of(
                        "code", "CHAT_STREAM_FAILED",
                        "message", ex.getMessage() != null ? ex.getMessage() : "处理失败"));
                sender.sendEvent(InterviewStreamEventType.DONE.value(), "[DONE]");
            }
            taskManager.unregister(taskId);
            sender.complete();
        } finally {
            RAGTraceContext.clear();
        }
    }

    private void sendChunked(InterviewSseEmitterSender sender, String channel, String content) {
        if (content == null || content.isBlank()) return;
        int length = content.length();
        int index = 0;
        while (index < length) {
            int end = Math.min(length, index + messageChunkSize);
            sender.sendEvent(InterviewStreamEventType.MESSAGE.value(), Map.of(
                    "channel", channel,
                    "delta", content.substring(index, end)));
            index = end;
        }
    }
}
