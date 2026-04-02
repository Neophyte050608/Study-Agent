package com.example.interview.service;

import com.example.interview.agent.KnowledgeQaAgent;
import com.example.interview.core.RAGTraceContext;
import com.example.interview.security.InputSanitizer;
import com.example.interview.stream.InterviewSseEmitterSender;
import com.example.interview.stream.InterviewStreamEventType;
import com.example.interview.stream.InterviewStreamTaskManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

@Service
public class ChatStreamingService {
    private static final Logger log = LoggerFactory.getLogger(ChatStreamingService.class);

    private final WebChatService webChatService;
    private final InterviewStreamTaskManager taskManager;
    private final KnowledgeQaAgent knowledgeQaAgent;
    private final InputSanitizer inputSanitizer;
    private final Executor streamingExecutor;
    private final long timeoutMillis;

    public ChatStreamingService(
            WebChatService webChatService,
            InterviewStreamTaskManager taskManager,
            KnowledgeQaAgent knowledgeQaAgent,
            InputSanitizer inputSanitizer,
            @Qualifier("interviewStreamingExecutor") Executor streamingExecutor,
            @Value("${app.chat.streaming.timeout-millis:180000}") long timeoutMillis) {
        this.webChatService = webChatService;
        this.taskManager = taskManager;
        this.knowledgeQaAgent = knowledgeQaAgent;
        this.inputSanitizer = inputSanitizer;
        this.streamingExecutor = streamingExecutor;
        this.timeoutMillis = timeoutMillis;
    }

    public SseEmitter streamChat(String sessionId, String userId, String content) {
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
                "sessionId", sessionId
        ));

        streamingExecutor.execute(() -> runChat(sessionId, userId, sanitizedContent, traceId, taskId, sender));
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

            sender.sendEvent(InterviewStreamEventType.PROGRESS.value(), Map.of(
                    "stage", "RETRIEVING", "label", "正在检索知识", "status", "running", "percent", 50));

            sender.sendEvent(InterviewStreamEventType.PROGRESS.value(), Map.of(
                    "stage", "GENERATING", "label", "正在生成回答", "status", "running", "percent", 70));

            // 真流式：逐 token 通过 SSE 回调发送
            StringBuilder fullAnswer = new StringBuilder();
            Map<String, Object> result = knowledgeQaAgent.executeStream(
                    content, history,
                    token -> {
                        if (!taskManager.isCancelled(taskId)) {
                            fullAnswer.append(token);
                            sender.sendEvent(InterviewStreamEventType.MESSAGE.value(), Map.of(
                                    "channel", "answer",
                                    "delta", token));
                        }
                    }
            );

            if (taskManager.isCancelled(taskId)) return;

            String replyText = fullAnswer.toString();

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("traceId", traceId);
            if (result.containsKey("sources")) {
                metadata.put("sources", result.get("sources"));
            }
            webChatService.saveAssistantMessage(sessionId, replyText, metadata);
            webChatService.autoTitleIfNeeded(sessionId, content);

            sender.sendEvent(InterviewStreamEventType.FINISH.value(), Map.of(
                    "action", "chat",
                    "result", Map.of("content", replyText, "traceId", traceId)));
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
}
