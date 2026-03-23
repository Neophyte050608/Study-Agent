package com.example.interview.agent;

import com.example.interview.service.DynamicModelFactory;
import com.example.interview.service.LearningEvent;
import com.example.interview.service.LearningProfileAgent;
import com.example.interview.service.LearningSource;
import com.example.interview.service.RAGService;
import com.example.interview.modelrouting.ModelRouteType;
import com.example.interview.modelrouting.RoutingChatService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 编程练习智能体（CodingPracticeAgent）。
 * 负责算法题、编程题的生成、提交评测以及练习状态管理。
 * 集成了 RAG 服务用于题目生成和评估，并与学习画像系统联动。
 */
@Component
public class CodingPracticeAgent implements Agent<Map<String, Object>, Map<String, Object>> {
    /** RAG 服务，用于生成题目和评估回答 */
    private final RAGService ragService;
    /** 学习画像智能体，用于获取用户背景和记录练习结果 */
    private final LearningProfileAgent learningProfileAgent;
    /** 动态模型工厂 */
    private final DynamicModelFactory dynamicModelFactory;
    /** Prompt 模板服务 */
    private final com.example.interview.service.PromptManager promptManager;
    /** JSON 处理 */
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 内存中的编程练习会话缓存 */
    private final Map<String, CodingSession> sessions = new ConcurrentHashMap<>();
    /** 自定义画像异步更新线程池 */
    private final java.util.concurrent.Executor profileUpdateExecutor;
    /** 路由服务 */
    private final RoutingChatService routingChatService;

    public CodingPracticeAgent(RAGService ragService, LearningProfileAgent learningProfileAgent, DynamicModelFactory dynamicModelFactory, com.example.interview.service.PromptManager promptManager, @org.springframework.beans.factory.annotation.Qualifier("profileUpdateExecutor") java.util.concurrent.Executor profileUpdateExecutor, RoutingChatService routingChatService) {
        this.ragService = ragService;
        this.learningProfileAgent = learningProfileAgent;
        this.dynamicModelFactory = dynamicModelFactory;
        this.promptManager = promptManager;
        this.profileUpdateExecutor = profileUpdateExecutor;
        this.routingChatService = routingChatService;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input) {
        String action = text(input, "action").toLowerCase();
        if ("chat".equals(action)) {
            return handleChat(input);
        }
        if (action.isBlank() || "start".equals(action)) {
            return startPractice(input);
        }
        if ("submit".equals(action)) {
            return submitPractice(input);
        }
        if ("state".equals(action)) {
            return statePractice(input);
        }
        return Map.of(
                "agent", "CodingPracticeAgent",
                "status", "bad_request",
                "message", "不支持的 action: " + action
        );
    }

    /**
     * 处理沉浸式对话流：解析意图、出题或评估。
     */
    private Map<String, Object> handleChat(Map<String, Object> input) {
        String sessionId = text(input, "sessionId");
        String message = text(input, "message");
        String userId = learningProfileAgent.normalizeUserId(text(input, "userId"));

        // 1. 如果没有 sessionId，说明是新的一轮对话，进行意图识别并开启刷题
        if (sessionId.isBlank()) {
            return startNewChatSession(userId, message);
        }

        // 2. 如果有 sessionId，检查当前状态
        CodingSession session = sessions.get(sessionId);
        if (session == null) {
            return Map.of("agent", "CodingPracticeAgent", "status", "not_found", "message", "Session已失效");
        }

        // 3. 如果已有题目等待回答，则作为答案提交评估
        if (session.currentQuestion() != null && !session.currentQuestion().isBlank()) {
            return evaluateChatAnswer(session, message);
        }

        // 4. 其他状态，继续出题（比如刚刚评估完，接着出下一题）
        return generateNextChatQuestion(session);
    }

    private Map<String, Object> startNewChatSession(String userId, String message) {
        // 意图识别
        // 注意：这里仍然可以用 promptTemplateService.loadFewShotCases 也可以把它挪走，但我们保留它因为只是加载JSON
        List<Map<String, Object>> cases = new java.util.ArrayList<>();
        try {
            org.springframework.core.io.ClassPathResource resource = new org.springframework.core.io.ClassPathResource("prompts/intent_cases.json");
            if (resource.exists()) {
                cases = objectMapper.readValue(resource.getInputStream(), new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
            }
        } catch (Exception e) {
            System.err.println("Failed to load intent_cases.json: " + e.getMessage());
        }

        String prompt = promptManager.render("coding-intent", Map.of("message", message, "cases", cases));
        System.out.println("====== [CodingPracticeAgent - Intent] Prompt ======");
        System.out.println(prompt);
        // 使用路由服务进行意图识别
        String jsonStr = routingChatService.call(
            "你是一个意图识别助手。请从用户的输入中提取刷题意图。\n" + prompt, 
            ModelRouteType.GENERAL, 
            "刷题意图识别"
        );
        System.out.println("====== [CodingPracticeAgent - Intent] Response ======");
        System.out.println(jsonStr);
        
        String topic = "";
        String type = "算法";
        int count = 1;
        String difficulty = "medium";
        try {
            // 清理可能包含 markdown 标记的 JSON 字符串
            if (jsonStr != null) {
                jsonStr = jsonStr.replaceAll("```json", "").replaceAll("```", "").trim();
                JsonNode node = objectMapper.readTree(jsonStr);
                if (node.has("topic") && !node.get("topic").asText().isBlank() && !"null".equals(node.get("topic").asText())) {
                    topic = node.get("topic").asText();
                }
                if (node.has("type") && !node.get("type").asText().isBlank()) type = node.get("type").asText();
                if (node.has("count") && node.get("count").asInt() > 0) count = node.get("count").asInt();
                if (node.has("difficulty") && !node.get("difficulty").asText().isBlank()) difficulty = node.get("difficulty").asText();
            }
        } catch (JsonProcessingException e) {
            // 解析失败使用默认值
        }

        if (topic.isBlank()) {
            List<String> recommended = learningProfileAgent.snapshot(userId).recommendedNextCodingTopics();
            if (!recommended.isEmpty()) {
                topic = recommended.getFirst();
            } else {
                topic = "基础算法";
            }
        }

        String sessionId = UUID.randomUUID().toString();
        CodingSession session = new CodingSession(sessionId, userId, topic, difficulty, type, count, 0, "", 0, 0, Instant.now());
        
        return generateNextChatQuestion(session);
    }

    private Map<String, Object> generateNextChatQuestion(CodingSession session) {
        if (session.currentQuestionIndex() >= session.totalQuestions()) {
            return Map.of(
                "agent", "CodingPracticeAgent",
                "status", "completed",
                "message", "本次刷题已全部完成！",
                "sessionId", session.sessionId()
            );
        }

        String resolvedProfileSnapshot = learningProfileAgent.snapshotForPrompt(session.userId(), session.topic());
        String question = ragService.generateCodingQuestion(buildTopicWithType(session.topic(), session.type()), session.difficulty(), resolvedProfileSnapshot);
        if (question == null || question.isBlank()) {
            question = buildQuestion(session.topic(), session.difficulty()) + " (" + session.type() + ")";
        }

        CodingSession updatedSession = new CodingSession(
            session.sessionId(), session.userId(), session.topic(), session.difficulty(), session.type(),
            session.totalQuestions(), session.currentQuestionIndex() + 1, question, session.attempts(), session.bestScore(), session.createdAt()
        );
        sessions.put(session.sessionId(), updatedSession);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agent", "CodingPracticeAgent");
        result.put("status", "question_generated");
        result.put("sessionId", updatedSession.sessionId());
        result.put("question", question);
        result.put("progress", updatedSession.currentQuestionIndex() + "/" + updatedSession.totalQuestions());
        return result;
    }

    private Map<String, Object> evaluateChatAnswer(CodingSession session, String answer) {
        RAGService.CodingAssessment assessment = ragService.evaluateCodingAnswer(
                buildTopicWithType(session.topic(), session.type()), session.difficulty(), session.currentQuestion(), answer
        );
        if (assessment == null) {
            assessment = fallbackAssessment(answer, session.topic());
        }
        final RAGService.CodingAssessment finalAssessment = assessment;

        int score = assessment.score();
        int attempts = session.attempts() + 1;
        int bestScore = Math.max(session.bestScore(), score);

        // 异步写入画像，使用自定义线程池 profileUpdateExecutor，避免阻塞评估结果返回和占用默认池
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                System.out.println("====== [CodingPracticeAgent] 异步更新学习画像开始 ======");
                learningProfileAgent.upsertEvent(new LearningEvent(
                        "coding-" + session.sessionId() + "-" + attempts,
                        session.userId(),
                        LearningSource.CODING,
                        session.topic(),
                        score,
                        weakPointsByScore(score, finalAssessment.feedback(), finalAssessment.nextHint()),
                        familiarPointsByScore(score, finalAssessment.feedback()),
                        finalAssessment.feedback(),
                        Instant.now()
                ));
                System.out.println("====== [CodingPracticeAgent] 异步更新学习画像完成 ======");
            } catch (Exception e) {
                System.err.println("====== [CodingPracticeAgent] 异步更新学习画像失败: " + e.getMessage() + " ======");
            }
        }, profileUpdateExecutor);

        // 清空当前题目，等待下一次请求生成新题目
        CodingSession updatedSession = new CodingSession(
            session.sessionId(), session.userId(), session.topic(), session.difficulty(), session.type(),
            session.totalQuestions(), session.currentQuestionIndex(), "", attempts, bestScore, session.createdAt()
        );
        sessions.put(session.sessionId(), updatedSession);

        return Map.of(
                "agent", "CodingPracticeAgent",
                "status", "evaluated",
                "sessionId", session.sessionId(),
                "score", score,
                "feedback", assessment.feedback(),
                "progress", session.currentQuestionIndex() + "/" + session.totalQuestions(),
                "isLast", session.currentQuestionIndex() >= session.totalQuestions()
        );
    }

    /**
     * 开始一个新的编程练习。
     * 如果未指定主题，将根据用户画像推荐合适的主题。
     */
    private Map<String, Object> startPractice(Map<String, Object> input) {
        String userId = learningProfileAgent.normalizeUserId(text(input, "userId"));
        String topic = text(input, "topic");
        String difficulty = text(input, "difficulty");
        String type = text(input, "type");
        String profileSnapshot = text(input, "profileSnapshot");
        String recommendedTopic = "";
        
        // 如果没有传入主题，尝试从画像推荐
        if (topic.isBlank()) {
            List<String> recommended = learningProfileAgent.snapshot(userId).recommendedNextCodingTopics();
            if (!recommended.isEmpty()) {
                recommendedTopic = recommended.getFirst();
            }
        }
        
        // 归一化主题、难度、类型和画像快照
        String normalizedTopic = topic.isBlank() ? (recommendedTopic.isBlank() ? "数组与字符串" : recommendedTopic) : topic;
        String normalizedDifficulty = difficulty.isBlank() ? "medium" : difficulty.toLowerCase();
        String normalizedType = type.isBlank() ? "算法题" : type;
        
        String resolvedProfileSnapshot = profileSnapshot.isBlank()
                ? learningProfileAgent.snapshotForPrompt(userId, normalizedTopic)
                : profileSnapshot;
        
        String sessionId = UUID.randomUUID().toString();
        // 调用 RAG 生成题目，将类型融入主题描述中以获得更准确的题目生成
        String ragTopic = buildTopicWithType(normalizedTopic, normalizedType);
        String question = ragService.generateCodingQuestion(ragTopic, normalizedDifficulty, resolvedProfileSnapshot);
        if (question == null || question.isBlank()) {
            question = buildQuestion(normalizedTopic, normalizedDifficulty) + " (" + normalizedType + ")";
        }
        
        // 保存会话并返回结果
        sessions.put(sessionId, new CodingSession(sessionId, userId, normalizedTopic, normalizedDifficulty, normalizedType, 1, 1, question, 0, 0, Instant.now()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agent", "CodingPracticeAgent");
        result.put("status", "started");
        result.put("sessionId", sessionId);
        result.put("userId", userId);
        result.put("topic", normalizedTopic);
        result.put("difficulty", normalizedDifficulty);
        result.put("type", normalizedType);
        result.put("question", question);
        result.put("profileSnapshotApplied", !resolvedProfileSnapshot.isBlank());
        result.put("topicFromProfile", topic.isBlank() && !recommendedTopic.isBlank());
        return result;
    }

    /**
     * 提交编程练习答案并进行评估。
     * 评估结果将同步更新到用户的学习画像中。
     */
    private Map<String, Object> submitPractice(Map<String, Object> input) {
        String sessionId = text(input, "sessionId");
        String answer = text(input, "answer");
        if (sessionId.isBlank()) {
            return Map.of(
                    "agent", "CodingPracticeAgent",
                    "status", "bad_request",
                    "message", "sessionId 不能为空"
            );
        }
        CodingSession session = sessions.get(sessionId);
        if (session == null) {
            return Map.of(
                    "agent", "CodingPracticeAgent",
                    "status", "not_found",
                    "message", "未找到对应 coding session"
            );
        }
        
        // 调用 RAG 评估代码质量
        RAGService.CodingAssessment assessment = ragService.evaluateCodingAnswer(
                session.topic(),
                session.difficulty(),
                session.currentQuestion(),
                answer
        );
        if (assessment == null) {
            assessment = fallbackAssessment(answer, session.topic());
        }
        final RAGService.CodingAssessment finalAssessment = assessment;
        
        int score = assessment.score();
        int attempts = session.attempts() + 1;
        int bestScore = Math.max(session.bestScore(), score);
        
        // 更新会话状态
        sessions.put(sessionId, new CodingSession(
                session.sessionId(),
                session.userId(),
                session.topic(),
                session.difficulty(),
                session.type(),
                session.totalQuestions(),
                session.currentQuestionIndex(),
                session.currentQuestion(),
                attempts,
                bestScore,
                session.createdAt()
        ));
        
        // 将练习结果异步沉淀为学习事件写入画像，使用自定义线程池 profileUpdateExecutor
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                System.out.println("====== [CodingPracticeAgent] 异步更新学习画像开始 ======");
                learningProfileAgent.upsertEvent(new LearningEvent(
                        "coding-" + sessionId + "-" + attempts,
                        session.userId(),
                        LearningSource.CODING,
                        session.topic(),
                        score,
                        weakPointsByScore(score, finalAssessment.feedback(), finalAssessment.nextHint()),
                        familiarPointsByScore(score, finalAssessment.feedback()),
                        finalAssessment.feedback(),
                        Instant.now()
                ));
                System.out.println("====== [CodingPracticeAgent] 异步更新学习画像完成 ======");
            } catch (Exception e) {
                System.err.println("====== [CodingPracticeAgent] 异步更新学习画像失败: " + e.getMessage() + " ======");
            }
        }, profileUpdateExecutor);
        
        return Map.of(
                "agent", "CodingPracticeAgent",
                "status", "evaluated",
                "sessionId", sessionId,
                "userId", session.userId(),
                "attempts", attempts,
                "score", score,
                "bestScore", bestScore,
                "feedback", assessment.feedback(),
                "nextHint", assessment.nextHint(),
                "nextQuestion", assessment.nextQuestion()
        );
    }

    /**
     * 查询当前编程练习的状态。
     */
    private Map<String, Object> statePractice(Map<String, Object> input) {
        String sessionId = text(input, "sessionId");
        if (sessionId.isBlank()) {
            return Map.of(
                    "agent", "CodingPracticeAgent",
                    "status", "bad_request",
                    "message", "sessionId 不能为空"
            );
        }
        CodingSession session = sessions.get(sessionId);
        if (session == null) {
            return Map.of(
                    "agent", "CodingPracticeAgent",
                    "status", "not_found",
                    "message", "未找到对应 coding session"
            );
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agent", "CodingPracticeAgent");
        data.put("status", "ready");
        data.put("sessionId", session.sessionId());
        data.put("userId", session.userId());
        data.put("topic", session.topic());
        data.put("difficulty", session.difficulty());
        data.put("type", session.type());
        data.put("question", session.currentQuestion());
        data.put("attempts", session.attempts());
        data.put("bestScore", session.bestScore());
        data.put("createdAt", session.createdAt().toString());
        return data;
    }

    /**
     * 简单的代码评分评估逻辑（作为 RAG 失败时的降级方案）。
     */
    private int evaluate(String answer) {
        if (answer == null || answer.isBlank()) {
            return 20;
        }
        String text = answer.toLowerCase();
        int score = 35;
        if (text.length() >= 60) {
            score += 15;
        }
        if (text.contains("for") || text.contains("while")) {
            score += 15;
        }
        if (text.contains("hashmap") || text.contains("map") || text.contains("set")) {
            score += 10;
        }
        if (text.contains("o(")) {
            score += 15;
        }
        if (text.contains("边界") || text.contains("null") || text.contains("空")) {
            score += 10;
        }
        return Math.min(score, 100);
    }

    /**
     * 根据评分生成评语（降级方案）。
     */
    private String feedback(int score) {
        if (score < 60) {
            return "解题结构不完整，建议先补充思路、复杂度和边界处理。";
        }
        if (score < 80) {
            return "答案基本可用，建议再优化复杂度分析与异常输入处理。";
        }
        return "答案质量较高，可继续尝试同主题更高难度题。";
    }

    /**
     * 根据评分生成提示（降级方案）。
     */
    private String hint(int score, String topic) {
        if (score < 60) {
            return "先写出暴力解，再逐步优化到更优复杂度。";
        }
        if (score < 80) {
            return "请明确说明时间复杂度和空间复杂度。";
        }
        return "尝试给出另一种实现并比较 trade-off。";
    }

    /**
     * 降级评估逻辑入口。
     */
    private RAGService.CodingAssessment fallbackAssessment(String answer, String topic) {
        int score = evaluate(answer);
        String nextQuestion = buildQuestion(topic, score >= 80 ? "hard" : score >= 60 ? "medium" : "easy");
        return new RAGService.CodingAssessment(score, feedback(score), hint(score, topic), nextQuestion);
    }

    /**
     * 构建题目文本模板。
     */
    private String buildQuestion(String topic, String difficulty) {
        return "请实现一道" + difficulty + "难度的「" + topic + "」算法题，要求说明思路、复杂度与边界条件。";
    }

    private String buildTopicWithType(String topic, String type) {
        String normalizedTopic = topic == null ? "" : topic.trim();
        String normalizedType = type == null ? "" : type.trim();
        if (normalizedType.isBlank()) {
            return normalizedTopic;
        }
        if (normalizedTopic.isBlank()) {
            return normalizedType;
        }
        return normalizedTopic + "（" + normalizedType + "）";
    }

    /**
     * 安全地从 Map 中获取字符串值。
     */
    private String text(Map<String, Object> map, String key) {
        if (map == null) {
            return "";
        }
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * 根据得分确定薄弱点标签。
     */
    private List<String> weakPointsByScore(int score, String feedback, String hint) {
        List<String> points = new ArrayList<>();
        if (score < 60) {
            points.add("解题结构不完整");
            points.add("复杂度分析不足");
        } else if (score < 80) {
            points.add("边界条件覆盖不足");
        }
        if (feedback != null && !feedback.isBlank()) {
            points.add(feedback);
        }
        if (hint != null && !hint.isBlank()) {
            points.add(hint);
        }
        return points.stream().map(String::trim).filter(item -> !item.isBlank()).distinct().limit(6).toList();
    }

    /**
     * 根据得分确定熟练点标签。
     */
    private List<String> familiarPointsByScore(int score, String feedback) {
        if (score < 80) {
            return List.of();
        }
        List<String> points = new ArrayList<>();
        points.add("核心思路表达清晰");
        points.add("复杂度分析有效");
        if (feedback != null && !feedback.isBlank()) {
            points.add(feedback);
        }
        return points.stream().map(String::trim).filter(item -> !item.isBlank()).distinct().limit(6).toList();
    }

    /**
     * 编程练习会话的内部记录类。
     */
    private record CodingSession(
            String sessionId,
            String userId,
            String topic,
            String difficulty,
            String type,
            int totalQuestions,
            int currentQuestionIndex,
            String currentQuestion,
            int attempts,
            int bestScore,
            Instant createdAt
    ) {
    }
}
