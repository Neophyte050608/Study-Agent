package com.example.interview.service;

import com.example.interview.agent.InterviewOrchestratorAgent;
import com.example.interview.agent.KnowledgeQaAgent;
import com.example.interview.agent.TaskRouterAgent;
import com.example.interview.agent.task.TaskRequest;
import com.example.interview.agent.task.TaskResponse;
import com.example.interview.agent.task.TaskType;
import com.example.interview.core.InterviewSession;
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
    private final KnowledgeQaAgent knowledgeQaAgent;
    private final InterviewOrchestratorAgent interviewOrchestratorAgent;
    private final InputSanitizer inputSanitizer;
    private final Executor streamingExecutor;
    private final long timeoutMillis;

    public ChatStreamingService(
            WebChatService webChatService,
            InterviewStreamTaskManager taskManager,
            KnowledgeQaAgent knowledgeQaAgent,
            InterviewOrchestratorAgent interviewOrchestratorAgent,
            InputSanitizer inputSanitizer,
            @Qualifier("interviewStreamingExecutor") Executor streamingExecutor,
            @Value("${app.chat.streaming.timeout-millis:180000}") long timeoutMillis) {
        this.webChatService = webChatService;
        this.taskManager = taskManager;
        this.knowledgeQaAgent = knowledgeQaAgent;
        this.interviewOrchestratorAgent = interviewOrchestratorAgent;
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

            // 活跃面试会话检测：如果当前聊天中有进行中的面试，直接路由为面试答题
            String activeInterviewId = webChatService.findActiveInterviewSessionId(sessionId);
            if (activeInterviewId != null && !activeInterviewId.isBlank()) {
                InterviewSession interviewSession = interviewOrchestratorAgent.getSession(activeInterviewId);
                if (interviewSession != null && interviewSession.getHistory().size() < interviewSession.getTotalQuestions()) {
                    // 面试仍在进行中，强制路由为 INTERVIEW_ANSWER
                    Map<String, Object> answerPayload = new HashMap<>();
                    answerPayload.put("sessionId", activeInterviewId);
                    answerPayload.put("userAnswer", content);
                    TaskRequest answerRequest = new TaskRequest(TaskType.INTERVIEW_ANSWER, answerPayload, Map.of(
                            "sessionId", sessionId, "userId", userId, "history", history, "traceId", traceId));
                    TaskResponse answerResponse = webChatService.getTaskRouterAgent().dispatch(answerRequest);

                    if (taskManager.isCancelled(taskId)) return;

                    String replyText = webChatService.extractReplyText(answerResponse);
                    sender.sendEvent(InterviewStreamEventType.PROGRESS.value(), Map.of(
                            "stage", "GENERATING", "label", "正在生成评价", "status", "running", "percent", 70));
                    sendChunkedText(sender, replyText, taskId);

                    if (taskManager.isCancelled(taskId)) return;

                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("traceId", traceId);
                    metadata.put("interviewSessionId", activeInterviewId);
                    if (answerResponse.data() instanceof InterviewOrchestratorAgent.AnswerResult ar && ar.finished()) {
                        metadata.put("type", "interview_finished");
                    } else {
                        metadata.put("type", "interview_answer");
                    }
                    webChatService.saveAssistantMessage(sessionId, replyText, metadata);

                    sender.sendEvent(InterviewStreamEventType.FINISH.value(), Map.of(
                            "action", "chat",
                            "result", Map.of("content", replyText, "traceId", traceId)));
                    sender.sendEvent(InterviewStreamEventType.DONE.value(), "[DONE]");
                    taskManager.unregister(taskId);
                    sender.complete();
                    return;
                }
            }

            // 先走意图路由，判断是否为面试/编码等结构化意图
            Map<String, Object> payload = new HashMap<>();
            payload.put("query", content);
            Map<String, Object> context = new HashMap<>();
            context.put("sessionId", sessionId);
            context.put("userId", userId);
            context.put("history", history);
            context.put("traceId", traceId);

            TaskRequest request = new TaskRequest(null, payload, context);
            TaskResponse response = webChatService.getTaskRouterAgent().dispatch(request);

            if (taskManager.isCancelled(taskId)) return;

            // 判断是否为知识问答意图（可以走流式）
            if (isKnowledgeQaResult(response)) {
                // 走原有的 KnowledgeQaAgent 流式路径
                runKnowledgeQaStream(sessionId, content, history, traceId, taskId, sender);
            } else {
                // 非知识问答意图（面试、编码练习等），结果已计算完成，分块流式发送
                String replyText = webChatService.extractReplyText(response);

                sender.sendEvent(InterviewStreamEventType.PROGRESS.value(), Map.of(
                        "stage", "GENERATING", "label", "正在生成回答", "status", "running", "percent", 70));

                // 分块发送文本，模拟流式效果
                sendChunkedText(sender, replyText, taskId);

                if (taskManager.isCancelled(taskId)) return;

                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("traceId", traceId);
                if (response.data() instanceof InterviewSession session) {
                    metadata.put("interviewSessionId", session.getId());
                    metadata.put("type", "interview_start");
                } else if (response.data() instanceof InterviewOrchestratorAgent.AnswerResult ar) {
                    // AnswerResult 也可能通过意图路由返回（非活跃面试检测路径）
                    metadata.put("type", ar.finished() ? "interview_finished" : "interview_answer");
                }
                webChatService.saveAssistantMessage(sessionId, replyText, metadata);
                webChatService.autoTitleIfNeeded(sessionId, content);

                sender.sendEvent(InterviewStreamEventType.FINISH.value(), Map.of(
                        "action", "chat",
                        "result", Map.of("content", replyText, "traceId", traceId)));
                sender.sendEvent(InterviewStreamEventType.DONE.value(), "[DONE]");
                taskManager.unregister(taskId);
                sender.complete();
            }
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

    /**
     * 判断 TaskRouterAgent 返回结果是否为知识问答类型（应该走流式 KnowledgeQaAgent）。
     * 知识问答的特征：response.data() 是 Map 且包含 "answer" 键，或者返回失败/意图未识别。
     */
    private boolean isKnowledgeQaResult(TaskResponse response) {
        if (!response.success()) return true; // 失败时降级走知识问答
        Object data = response.data();
        if (data == null) return true;
        if (data instanceof Map<?, ?> map) {
            // 知识问答返回 Map{answer: ...}
            // 但面试/编码等也可能返回 Map{question: ...}，需区分
            return map.containsKey("answer") && !map.containsKey("clarification");
        }
        return false;
    }

    /**
     * 原有的 KnowledgeQaAgent 流式知识问答路径。
     */
    private void runKnowledgeQaStream(String sessionId, String content, String history,
                                       String traceId, String taskId, InterviewSseEmitterSender sender) {
        sender.sendEvent(InterviewStreamEventType.PROGRESS.value(), Map.of(
                "stage", "RETRIEVING", "label", "正在检索知识", "status", "running", "percent", 50));

        sender.sendEvent(InterviewStreamEventType.PROGRESS.value(), Map.of(
                "stage", "GENERATING", "label", "正在生成回答", "status", "running", "percent", 70));

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
    }

    /**
     * 分块发送已有文本，模拟流式效果。
     */
    private void sendChunkedText(InterviewSseEmitterSender sender, String text, String taskId) {
        if (text == null || text.isEmpty()) return;
        int chunkSize = 32;
        int index = 0;
        while (index < text.length()) {
            if (taskManager.isCancelled(taskId)) return;
            int end = Math.min(text.length(), index + chunkSize);
            sender.sendEvent(InterviewStreamEventType.MESSAGE.value(), Map.of(
                    "channel", "answer",
                    "delta", text.substring(index, end)));
            index = end;
        }
    }
}
