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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

@Service
public class ChatStreamingService {
    private static final Logger log = LoggerFactory.getLogger(ChatStreamingService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

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

            // 活跃面试会话检测：如果当前聊天中有进行中的面试，且用户消息不是新指令，路由为面试答题
            String activeInterviewId = webChatService.findActiveInterviewSessionId(sessionId);
            if (activeInterviewId != null && !activeInterviewId.isBlank() && !looksLikeNewIntent(content)) {
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

            // 活跃编码练习会话检测：如果当前聊天中有进行中的编码练习，且用户消息不是新指令，路由为编码答题
            String activeCodingId = webChatService.findActiveCodingSessionId(sessionId);
            if (activeCodingId != null && !activeCodingId.isBlank() && !looksLikeNewIntent(content)) {
                Map<String, Object> codingPayload = new HashMap<>();
                codingPayload.put("action", "chat");
                codingPayload.put("sessionId", activeCodingId);
                codingPayload.put("message", content);
                codingPayload.put("userId", userId);
                TaskRequest codingRequest = new TaskRequest(TaskType.CODING_PRACTICE, codingPayload, Map.of(
                        "sessionId", sessionId, "userId", userId, "history", history, "traceId", traceId));
                TaskResponse codingResponse = webChatService.getTaskRouterAgent().dispatch(codingRequest);

                if (taskManager.isCancelled(taskId)) return;

                String replyText = webChatService.extractReplyText(codingResponse);

                // 评估完如果还有下一题，自动追加生成
                boolean isLast = true;
                if (codingResponse.data() instanceof Map<?, ?> evalMap
                        && "evaluated".equals(evalMap.get("status"))) {
                    isLast = Boolean.TRUE.equals(evalMap.get("isLast"));
                    if (!isLast) {
                        // 再调一次 handleChat 生成下一题
                        Map<String, Object> nextPayload = new HashMap<>();
                        nextPayload.put("action", "chat");
                        nextPayload.put("sessionId", activeCodingId);
                        nextPayload.put("message", "");
                        nextPayload.put("userId", userId);
                        TaskRequest nextRequest = new TaskRequest(TaskType.CODING_PRACTICE, nextPayload, Map.of(
                                "sessionId", sessionId, "userId", userId, "history", history, "traceId", traceId));
                        TaskResponse nextResponse = webChatService.getTaskRouterAgent().dispatch(nextRequest);
                        if (nextResponse.success()) {
                            String nextText = webChatService.extractReplyText(nextResponse);
                            replyText = replyText + "\n\n---\n\n" + nextText;
                        }
                    }
                }

                sender.sendEvent(InterviewStreamEventType.PROGRESS.value(), Map.of(
                        "stage", "GENERATING", "label", "正在评估答案", "status", "running", "percent", 70));
                sendChunkedText(sender, replyText, taskId);

                if (taskManager.isCancelled(taskId)) return;

                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("traceId", traceId);
                metadata.put("type", "coding_practice");
                if (!isLast) {
                    metadata.put("codingSessionId", activeCodingId);
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
                
                // --- 新增：拦截 batch_quiz 分支 ---
                if (response.data() instanceof Map<?, ?> dataMap
                        && "batch_quiz".equals(dataMap.get("status"))
                        && dataMap.containsKey("quizPayload")) {
                    
                    sender.sendEvent(InterviewStreamEventType.PROGRESS.value(), Map.of(
                            "stage", "GENERATING", "label", "正在生成题目", "status", "running", "percent", 100));

                    // 下发专门的 quiz 事件
                    sender.sendEvent(InterviewStreamEventType.QUIZ.value(), dataMap.get("quizPayload"));

                    // 将 QuizPayload 序列化为 JSON 存入 DB，contentType 设为 "quiz"
                    String quizJson;
                    try {
                        quizJson = objectMapper.writeValueAsString(dataMap.get("quizPayload"));
                    } catch (Exception e) {
                        quizJson = "已为您生成批量选择题，请在答题卡中作答。";
                    }
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("traceId", traceId);
                    metadata.put("type", "coding_practice_batch");
                    metadata.put("codingSessionId", String.valueOf(dataMap.get("sessionId")));

                    webChatService.saveAssistantMessage(sessionId, quizJson, metadata, "quiz");

                    sender.sendEvent(InterviewStreamEventType.FINISH.value(), Map.of(
                            "action", "chat",
                            "result", Map.of("content", quizJson, "traceId", traceId)));
                    sender.sendEvent(InterviewStreamEventType.DONE.value(), "[DONE]");
                    taskManager.unregister(taskId);
                    sender.complete();
                    return;
                }
                // --- 新增结束 ---

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
                    metadata.put("type", ar.finished() ? "interview_finished" : "interview_answer");
                } else if (response.data() instanceof Map<?, ?> dataMap
                        && "CodingPracticeAgent".equals(dataMap.get("agent"))) {
                    Object codingSid = dataMap.get("sessionId");
                    if (codingSid != null) {
                        metadata.put("codingSessionId", String.valueOf(codingSid));
                    }
                    metadata.put("type", "coding_practice");
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
        Object images = result.get("images");
        if (images instanceof List<?> imageList && !imageList.isEmpty()) {
            metadata.put("images", images);
            sendImageEvents(sender, imageList, taskId);
            webChatService.saveAssistantMessage(sessionId, buildRichContent(replyText, imageList), metadata, "rich");
            sender.sendEvent(InterviewStreamEventType.FINISH.value(), Map.of(
                    "action", "chat",
                    "result", Map.of("content", replyText, "traceId", traceId, "images", imageList)));
        } else {
            webChatService.saveAssistantMessage(sessionId, replyText, metadata);
            sender.sendEvent(InterviewStreamEventType.FINISH.value(), Map.of(
                    "action", "chat",
                    "result", Map.of("content", replyText, "traceId", traceId)));
        }
        webChatService.autoTitleIfNeeded(sessionId, content);
        sender.sendEvent(InterviewStreamEventType.DONE.value(), "[DONE]");
        taskManager.unregister(taskId);
        sender.complete();
    }

    private void sendImageEvents(InterviewSseEmitterSender sender, List<?> images, String taskId) {
        for (Object image : images) {
            if (taskManager.isCancelled(taskId)) {
                return;
            }
            sender.sendEvent(InterviewStreamEventType.IMAGE.value(), image);
        }
    }

    private String buildRichContent(String text, List<?> images) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("text", text);
            List<Map<String, Object>> normalizedImages = new java.util.ArrayList<>();
            int position = text == null ? 0 : text.length();
            for (Object image : images) {
                if (image instanceof Map<?, ?> map) {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    normalized.put("imageId", map.get("imageId"));
                    normalized.put("imageName", map.get("imageName"));
                    normalized.put("accessUrl", map.get("accessUrl"));
                    normalized.put("thumbnailUrl", map.get("thumbnailUrl"));
                    normalized.put("summaryText", map.get("summaryText"));
                    normalized.put("retrieveChannel", map.get("retrieveChannel"));
                    normalized.put("position", position);
                    normalizedImages.add(normalized);
                } else {
                    normalizedImages.add(Map.of("position", position, "value", image));
                }
            }
            payload.put("images", normalizedImages);
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ignored) {
            return text;
        }
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
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    /**
     * 判断用户消息是否像是一个新意图指令（而非对当前面试题的回答）。
     * 用于在活跃面试会话中区分"回答当前题目"和"发起新操作"。
     */
    private static boolean looksLikeNewIntent(String content) {
        if (content == null || content.isBlank()) return false;
        String s = content.trim();
        return s.contains("来一") || s.contains("来两") || s.contains("来几")
                || s.contains("来道") || s.contains("出一") || s.contains("出道")
                || s.contains("刷题") || s.contains("选择题") || s.contains("填空题") || s.contains("算法题") || s.contains("场景题")
                || s.contains("开始面试") || s.contains("开启面试") || s.contains("模拟面试")
                || s.contains("来一场") || s.contains("换个") || s.contains("结束面试")
                || s.contains("生成报告") || s.contains("学习计划") || s.contains("学习画像")
                || s.contains("编码练习") || s.contains("停止");
    }
}
