package com.example.interview.im.service;

import com.example.interview.agent.InterviewOrchestratorAgent;
import com.example.interview.agent.TaskRouterAgent;
import com.example.interview.agent.task.TaskRequest;
import com.example.interview.agent.task.TaskResponse;
import com.example.interview.agent.task.TaskType;
import com.example.interview.config.IntentTreeProperties;
import com.example.interview.core.InterviewSession;
import com.example.interview.im.model.UnifiedMessage;
import com.example.interview.im.model.UnifiedReply;
import com.example.interview.service.IntentTreeRoutingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * IM 消息统一处理服务 (ImWebhookService)
 * 负责接收解析后的 IM 消息，维护会话上下文，并异步分发给任务路由中心 (TaskRouterAgent)。
 */
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class ImWebhookService {

    private static final Logger log = LoggerFactory.getLogger(ImWebhookService.class);

    private final StringRedisTemplate redisTemplate;
    private final ImConversationStore conversationStore;
    private final TaskRouterAgent taskRouterAgent;
    private final FeishuReplyAdapter feishuReplyAdapter;
    private final QqReplyAdapter qqReplyAdapter;
    private final IntentTreeProperties intentTreeProperties;
    private final IntentTreeRoutingService intentTreeRoutingService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ImWebhookService(
            StringRedisTemplate redisTemplate,
            ImConversationStore conversationStore,
            TaskRouterAgent taskRouterAgent,
            FeishuReplyAdapter feishuReplyAdapter,
            QqReplyAdapter qqReplyAdapter,
            IntentTreeProperties intentTreeProperties,
            IntentTreeRoutingService intentTreeRoutingService
    ) {
        this.redisTemplate = redisTemplate;
        this.conversationStore = conversationStore;
        this.taskRouterAgent = taskRouterAgent;
        this.feishuReplyAdapter = feishuReplyAdapter;
        this.qqReplyAdapter = qqReplyAdapter;
        this.intentTreeProperties = intentTreeProperties;
        this.intentTreeRoutingService = intentTreeRoutingService;
    }
    
    /** 事件幂等性缓存前缀 */
    private static final String EVENT_IDEMPOTENCY_PREFIX = "im:event:idempotency:";

    /**
     * 判断事件是否重复处理（幂等校验）
     */
    public boolean isDuplicateEvent(String eventId) {
        String key = EVENT_IDEMPOTENCY_PREFIX + eventId;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    /**
     * 记录事件处理状态，防止短时间内重复推送
     */
    public void recordEvent(String eventId) {
        String key = EVENT_IDEMPOTENCY_PREFIX + eventId;
        // 缓存 1 小时即可
        redisTemplate.opsForValue().set(key, "1", 1, TimeUnit.HOURS);
    }

    /**
     * 异步处理 IM 消息，避免阻塞 Webhook 响应线程
     */
    public void dispatchMessageAsync(UnifiedMessage message) {
        CompletableFuture.runAsync(() -> {
            try {
                log.info("开始异步处理 IM 消息: [{}] {}", message.getSessionId(), message.getContent());
                
                String content = message.getContent();
                String sessionId = message.getSessionId();

                // 1. 特殊指令处理
                if ("/clear".equals(content)) {
                    conversationStore.clearSession(sessionId);
                    sendReply(message, "上下文已清空。我们可以重新开始了！");
                    return;
                } else if ("/help".equals(content)) {
                    sendReply(message, "我是你的 AI 面试官助理。你可以尝试这样问我：\n" +
                            "1. 给我来一道 Java 相关的算法题\n" +
                            "2. 我们开启一场 Spring Boot 的模拟面试吧\n" +
                            "3. 帮我查询我的学习画像和计划");
                    return;
                }

                // 2. 更新并获取会话历史（用于增强 LLM 理解上下文的能力）
                conversationStore.addMessage(sessionId, "User", content);
                String history = conversationStore.getHistoryContext(sessionId);
                String activeInterviewId = conversationStore.getActiveSession(sessionId);
                String pendingClarification = conversationStore.getPendingClarification(sessionId);

                // 3. 构建统一任务请求 (TaskRequest)
                // 如果当前有活跃的面试会话，优先判定为回答面试问题
                TaskType forcedTaskType = null;
                Map<String, Object> payload = new HashMap<>();
                payload.put("query", content);

                if (activeInterviewId != null && !activeInterviewId.isBlank()) {
                    // 如果用户没有显式要求切换模式（如 /help, /clear 等已处理），则默认为回答上一题
                    forcedTaskType = TaskType.INTERVIEW_ANSWER;
                    payload.put("sessionId", activeInterviewId);
                    payload.put("userAnswer", content);
                } else if (pendingClarification != null && !pendingClarification.isBlank()) {
                    TaskType clarifiedTaskType = resolveClarificationTaskType(pendingClarification, content);
                    if (clarifiedTaskType != null) {
                        forcedTaskType = clarifiedTaskType;
                        payload.putAll(buildClarifiedPayload(clarifiedTaskType, content, pendingClarification));
                        conversationStore.clearPendingClarification(sessionId);
                    }
                }
                
                Map<String, Object> context = new HashMap<>();
                context.put("sessionId", sessionId);
                context.put("userId", message.getSenderId());
                context.put("history", history);
                context.put("traceId", UUID.randomUUID().toString());

                TaskRequest request = new TaskRequest(forcedTaskType, payload, context);

                // 4. 调用任务路由器进行分发处理
                TaskResponse response = taskRouterAgent.dispatch(request);

                // 5. 格式化执行结果并异步回复给用户
                String replyText;
                if (response.success()) {
                    replyText = formatResponse(response);
                    // 将 AI 的回复也存入历史，保持对话连续性
                    conversationStore.addMessage(sessionId, "AI", replyText);
                    captureClarificationState(sessionId, response);
                    
                    // 如果是新开启的面试会话，记录 activeSessionId
                    if (response.data() instanceof InterviewSession session) {
                        conversationStore.setActiveSession(sessionId, session.getId());
                    }
                    // 如果面试已结束，清除 activeSessionId
                    if (response.data() instanceof InterviewOrchestratorAgent.AnswerResult result && result.finished()) {
                        conversationStore.clearSession(sessionId); // 这里也可以只清 activeSession
                    }
                } else {
                    replyText = "抱歉，处理您的请求时遇到了问题：" + response.message();
                }

                sendReply(message, replyText);
                
            } catch (Exception e) {
                log.error("异步处理 IM 消息过程中发生异常", e);
                sendReply(message, "抱歉，系统内部发生错误，请稍后再试。");
            }
        });
    }

    TaskType resolveClarificationTaskType(String clarificationState, String userReply) {
        try {
            JsonNode root = objectMapper.readTree(clarificationState);
            JsonNode options = root.path("options");
            if (!options.isArray() || options.isEmpty()) {
                return null;
            }
            String normalizedReply = userReply == null ? "" : userReply.trim();
            if (normalizedReply.matches("^\\d+$")) {
                int selected = Integer.parseInt(normalizedReply);
                if (selected >= 1 && selected <= options.size()) {
                    String taskType = options.get(selected - 1).path("taskType").asText("");
                    return parseTaskType(taskType);
                }
            }
            for (JsonNode option : options) {
                String label = option.path("label").asText("");
                String taskType = option.path("taskType").asText("");
                if (!label.isBlank() && normalizedReply.contains(label)) {
                    return parseTaskType(taskType);
                }
                if (!taskType.isBlank() && normalizedReply.toUpperCase().contains(taskType.toUpperCase())) {
                    return parseTaskType(taskType);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    Map<String, Object> buildClarifiedPayload(TaskType clarifiedTaskType, String userReply, String clarificationState) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("clarificationResolved", true);
        String originalQuery = readOriginalQuery(clarificationState);
        String normalizedReply = userReply == null ? "" : userReply.trim();
        if (!originalQuery.isBlank()) {
            payload.put("query", (originalQuery + " " + normalizedReply).trim());
        }
        String normalizedText = cleanupClarificationReply(normalizedReply);
        if (clarifiedTaskType == TaskType.CODING_PRACTICE) {
            if (normalizedText.contains("选择")) {
                payload.put("questionType", "CHOICE");
                payload.put("type", "选择题");
            } else if (normalizedText.contains("填空")) {
                payload.put("questionType", "FILL");
                payload.put("type", "填空题");
            } else if (normalizedText.contains("算法")) {
                payload.put("questionType", "ALGORITHM");
                payload.put("type", "算法题");
            }
            if (normalizedText.contains("简单") || normalizedText.contains("easy")) {
                payload.put("difficulty", "easy");
            } else if (normalizedText.contains("困难") || normalizedText.contains("hard")) {
                payload.put("difficulty", "hard");
            } else if (normalizedText.contains("中等") || normalizedText.contains("medium")) {
                payload.put("difficulty", "medium");
            }
            java.util.regex.Matcher countMatcher = java.util.regex.Pattern.compile("(\\d+)\\s*(道|题|个)").matcher(normalizedText);
            if (countMatcher.find()) {
                payload.put("count", Integer.parseInt(countMatcher.group(1)));
            }
            String topic = extractTopic(normalizedText);
            if (!topic.isBlank()) {
                payload.put("topic", topic);
            }
        } else if (clarifiedTaskType == TaskType.INTERVIEW_START) {
            if (normalizedText.contains("跳过自我介绍") || normalizedText.contains("直接出题") || normalizedText.contains("跳过介绍")) {
                payload.put("skipIntro", true);
            }
            String topic = extractTopic(normalizedText);
            if (!topic.isBlank()) {
                payload.put("topic", topic);
            }
        } else if (clarifiedTaskType == TaskType.PROFILE_TRAINING_PLAN_QUERY) {
            if (normalizedText.contains("学习")) {
                payload.put("mode", "learning");
            } else if (normalizedText.contains("面试")) {
                payload.put("mode", "interview");
            }
        }
        String mergedQuery = String.valueOf(payload.getOrDefault("query", normalizedReply));
        Map<String, Object> refinedSlots = intentTreeRoutingService == null ? Map.of()
                : intentTreeRoutingService.refineSlots(clarifiedTaskType.name(), mergedQuery, "");
        mergeMissingPayload(payload, refinedSlots);
        return payload;
    }

    private void mergeMissingPayload(Map<String, Object> payload, Map<String, Object> refinedSlots) {
        if (refinedSlots == null || refinedSlots.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : refinedSlots.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || key.isBlank() || value == null || String.valueOf(value).isBlank()) {
                continue;
            }
            if (!payload.containsKey(key) || String.valueOf(payload.get(key)).isBlank()) {
                payload.put(key, value);
            }
        }
    }

    private String readOriginalQuery(String clarificationState) {
        try {
            JsonNode root = objectMapper.readTree(clarificationState);
            return root.path("originalQuery").asText("").trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String cleanupClarificationReply(String content) {
        if (content == null) {
            return "";
        }
        String normalized = content.trim();
        normalized = normalized.replaceFirst("^\\d+\\s*[)）.、:-]?\\s*", "");
        return normalized.trim();
    }

    private String extractTopic(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        List<String> topicKeywords = List.of("Java", "Spring Boot", "Spring", "Redis", "MySQL", "JVM", "并发", "算法", "网络", "操作系统");
        for (String keyword : topicKeywords) {
            if (text.toLowerCase().contains(keyword.toLowerCase())) {
                return keyword;
            }
        }
        return "";
    }

    private TaskType parseTaskType(String taskType) {
        if (taskType == null || taskType.isBlank() || "UNKNOWN".equalsIgnoreCase(taskType)) {
            return null;
        }
        try {
            return TaskType.valueOf(taskType.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void captureClarificationState(String sessionId, TaskResponse response) {
        if (!(response.data() instanceof Map<?, ?> data)) {
            conversationStore.clearPendingClarification(sessionId);
            return;
        }
        Object clarification = data.containsKey("clarification") ? data.get("clarification") : "false";
        if (!Boolean.parseBoolean(String.valueOf(clarification))) {
            conversationStore.clearPendingClarification(sessionId);
            return;
        }
        Object options = data.get("clarificationOptions");
        if (!(options instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        try {
            Map<String, Object> state = new HashMap<>();
            state.put("options", list);
            Object originalQuery = data.containsKey("originalQuery") ? data.get("originalQuery") : "";
            state.put("originalQuery", String.valueOf(originalQuery));
            String stateJson = objectMapper.writeValueAsString(state);
            conversationStore.setPendingClarification(sessionId, stateJson, intentTreeProperties.getClarificationTtlMinutes());
        } catch (Exception ignored) {
        }
    }

    /**
     * 调用飞书适配器发送消息回复
     */
    private void sendReply(UnifiedMessage message, String text) {
        UnifiedReply reply = UnifiedReply.builder()
                .platform(message.getPlatform())
                .sessionId(message.getSessionId())
                .replyToMessageId(message.getMessageId())
                .text(text)
                .traceId(UUID.randomUUID().toString())
                .build();
        
        if ("feishu".equals(message.getPlatform())) {
            feishuReplyAdapter.sendReply(reply);
        } else if ("qq".equals(message.getPlatform())) {
            qqReplyAdapter.sendReply(reply);
        }
    }

    /**
     * 将 TaskResponse 转换为用户可读的纯净文本格式。
     * 隐藏技术元数据（如 agent, sessionId），仅返回核心业务内容。
     */
    private String formatResponse(TaskResponse response) {
        Object data = response.data();
        if (data == null) {
            return response.message() != null ? response.message() : "任务执行完毕。";
        }
        
        StringBuilder sb = new StringBuilder();

        // 1. 处理面试会话开始 (InterviewSession)
        if (data instanceof InterviewSession session) {
            return session.getCurrentQuestion();
        }

        // 2. 处理答题结果 (AnswerResult)
        if (data instanceof InterviewOrchestratorAgent.AnswerResult result) {
            sb.append("【评分】").append(result.score()).append("\n\n");
            sb.append("【反馈】\n").append(result.feedback()).append("\n\n");
            if (result.finished()) {
                sb.append("恭喜！本次模拟面试已结束。你可以发送“生成报告”来查看最终评估。");
            } else {
                sb.append("【下一题】\n").append(result.nextQuestion());
            }
            return sb.toString();
        }

        // 3. 处理最终报告 (FinalReport)
        if (data instanceof InterviewOrchestratorAgent.FinalReport report) {
            sb.append("====== 面试复盘报告 ======\n\n");
            sb.append("【总评】\n").append(report.summary()).append("\n\n");
            sb.append("【薄弱环节】\n").append(report.weak()).append("\n\n");
            sb.append("【错误点】\n").append(report.wrong()).append("\n\n");
            sb.append("【平均分】").append(String.format("%.1f", report.averageScore())).append("\n\n");
            sb.append("【后续建议】\n").append(report.nextFocus());
            return sb.toString();
        }

        // 4. 处理通用 Map 结构（如刷题、画像查询等）
        if (data instanceof Map<?, ?> map) {
            // 优先提取核心业务字段，避免展示全部元数据
            if (map.containsKey("question")) {
                return String.valueOf(map.get("question"));
            }
            if (map.containsKey("recommendation")) {
                return String.valueOf(map.get("recommendation"));
            }
            if (map.containsKey("summary")) {
                return String.valueOf(map.get("summary"));
            }

            // 如果没有核心字段，则有选择地展示非内部字段
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                // 排除常见的技术元数据
                if (List.of("agent", "sessionId", "userId", "status", "traceId", "profileSnapshotApplied").contains(key)) {
                    continue;
                }
                sb.append("• ").append(key).append(": ").append(entry.getValue()).append("\n");
            }
            
            String result = sb.toString().trim();
            return result.isEmpty() ? "处理成功。" : result;
        }

        // 5. 兜底处理
        return String.valueOf(data);
    }
}
