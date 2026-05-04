package io.github.imzmq.interview.agent.runtime;

import io.github.imzmq.interview.agent.core.Agent;
import io.github.imzmq.interview.modelruntime.application.DynamicModelFactory;
import io.github.imzmq.interview.learning.application.LearningEvent;
import io.github.imzmq.interview.learning.application.LearningProfileAgent;
import io.github.imzmq.interview.learning.application.LearningSource;
import io.github.imzmq.interview.knowledge.application.RAGService;
import io.github.imzmq.interview.modelrouting.core.ModelRouteType;
import io.github.imzmq.interview.modelrouting.core.RoutingChatService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger logger = LoggerFactory.getLogger(CodingPracticeAgent.class);
    /** RAG 服务，用于生成题目和评估回答 */
    private final RAGService ragService;
    /** 学习画像智能体，用于获取用户背景和记录练习结果 */
    private final LearningProfileAgent learningProfileAgent;
    /** 动态模型工厂 */
    private final DynamicModelFactory dynamicModelFactory;
    /** Prompt 模板服务 */
    private final io.github.imzmq.interview.chat.application.PromptManager promptManager;
    /** JSON 处理 */
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 内存中的编程练习会话缓存 */
    private final Map<String, CodingSession> sessions = new ConcurrentHashMap<>();
    /** 自定义画像异步更新线程池 */
    private final java.util.concurrent.Executor profileUpdateExecutor;
    /** 路由服务 */
    private final RoutingChatService routingChatService;

    public CodingPracticeAgent(RAGService ragService, LearningProfileAgent learningProfileAgent, DynamicModelFactory dynamicModelFactory, io.github.imzmq.interview.chat.application.PromptManager promptManager, @org.springframework.beans.factory.annotation.Qualifier("profileUpdateExecutor") java.util.concurrent.Executor profileUpdateExecutor, RoutingChatService routingChatService) {
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
        if ("submit-scenario-card".equals(action)) {
            return submitScenarioCard(input);
        }
        if ("next-scenario-card".equals(action)) {
            return nextScenarioCard(input);
        }
        if ("submit-fill-card".equals(action)) {
            return submitFillCard(input);
        }
        if ("next-fill-card".equals(action)) {
            return nextFillCard(input);
        }
        if ("batch-quiz-submit".equals(action)) {
            return submitBatchQuizResults(input);
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
        List<String> excludedTopics = readTextList(input, "excludedTopics");

        // 1. 如果没有 sessionId，说明是新的一轮对话，进行意图识别并开启刷题
        if (sessionId.isBlank()) {
            return startNewChatSession(userId, message, excludedTopics);
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

    private Map<String, Object> startNewChatSession(String userId, String message, List<String> excludedTopics) {
        // 意图识别
        // 注意：这里仍然可以用 promptTemplateService.loadFewShotCases 也可以把它挪走，但我们保留它因为只是加载JSON
        List<Map<String, Object>> cases = new java.util.ArrayList<>();
        try {
            org.springframework.core.io.ClassPathResource resource = new org.springframework.core.io.ClassPathResource("prompts/intent_cases.json");
            if (resource.exists()) {
                cases = objectMapper.readValue(resource.getInputStream(), new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
            }
        } catch (Exception e) {
            logger.debug("加载 intent_cases.json 失败: {}", e.getMessage());
        }

        io.github.imzmq.interview.chat.application.PromptManager.PromptPair pair = promptManager.renderSplit(
                "coding-coach", "coding-intent", Map.of("message", message, "cases", cases));
        logger.debug("[CodingPracticeAgent - Intent] Prompt length={}", pair.userPrompt().length());
        String jsonStr = routingChatService.call(
                pair.systemPrompt(),
                pair.userPrompt(),
                ModelRouteType.GENERAL,
                "刷题意图识别"
        );
        logger.debug("[CodingPracticeAgent - Intent] Response length={}", jsonStr == null ? 0 : jsonStr.length());
        
        String topic = "";
        String type = "选择题";
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
        type = normalizePracticeType(type, message + " " + topic);
        // 兜底：从用户原始消息中正则提取数量
        if (count <= 1) {
            int extracted = extractCountFromText(message);
            if (extracted > 1) {
                count = Math.min(extracted, 10);
            }
        }
        logger.info("[startNewChatSession] message='{}', type='{}', count={}", message, type, count);

        if (topic.isBlank()) {
            List<String> recommended = learningProfileAgent.snapshot(userId).recommendedNextCodingTopics();
            if (!recommended.isEmpty()) {
                topic = recommended.getFirst();
            } else {
                topic = "基础算法";
            }
        }

        String sessionId = UUID.randomUUID().toString();
        CodingSession session = new CodingSession(sessionId, userId, topic, difficulty, type, count, 0, "", "", 0, 0, Instant.now(), excludedTopics);

        // 选择题统一走交互式答题卡模式（含单题），避免单题退化为纯文本输出
        if (type.contains("选择") && count >= 1) {
            return handleBatchQuiz(userId, topic, difficulty, count,
                learningProfileAgent.snapshotForPrompt(userId, topic), excludedTopics);
        }

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
        String question = ragService.generateCodingQuestion(buildTopicWithType(session.topic(), session.type()), session.difficulty(), resolvedProfileSnapshot, session.excludedTopics());
        if (question == null || question.isBlank()) {
            question = buildQuestion(session.topic(), session.difficulty(), session.type()) + buildExclusionSuffix(session.excludedTopics()) + " (" + session.type() + ")";
        }
        String cardId = "";
        if (isScenarioType(session.type())) {
            cardId = "scenario_" + UUID.randomUUID();
        } else if (isFillType(session.type())) {
            cardId = "fill_" + UUID.randomUUID();
        }

        CodingSession updatedSession = new CodingSession(
            session.sessionId(), session.userId(), session.topic(), session.difficulty(), session.type(),
            session.totalQuestions(), session.currentQuestionIndex() + 1, question, cardId, session.attempts(), session.bestScore(), session.createdAt(), session.excludedTopics()
        );
        sessions.put(session.sessionId(), updatedSession);

        if (isScenarioType(updatedSession.type())) {
            ScenarioPayload payload = buildScenarioPayload(updatedSession, question, cardId, "", false, false, null, "", "", "", false);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("agent", "CodingPracticeAgent");
            result.put("status", "scenario_question_generated");
            result.put("sessionId", updatedSession.sessionId());
            result.put("scenarioPayload", payload);
            return result;
        }
        if (isFillType(updatedSession.type())) {
            FillPayload payload = buildFillPayload(updatedSession, question, cardId, "", false, false, null, "", "", "", false);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("agent", "CodingPracticeAgent");
            result.put("status", "fill_question_generated");
            result.put("sessionId", updatedSession.sessionId());
            result.put("fillPayload", payload);
            return result;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agent", "CodingPracticeAgent");
        result.put("status", "question_generated");
        result.put("sessionId", updatedSession.sessionId());
        result.put("question", question);
        result.put("progress", updatedSession.currentQuestionIndex() + "/" + updatedSession.totalQuestions());
        return result;
    }

    /**
     * 批量选择题模式：一次生成所有题目，前端交互式答题。
     */
    private Map<String, Object> handleBatchQuiz(String userId, String topic,
            String difficulty, int count, String profileSnapshot, List<String> excludedTopics) {
        List<QuizQuestion> questions = ragService.generateBatchQuiz(topic, difficulty, count, profileSnapshot, excludedTopics);

        String sessionId = UUID.randomUUID().toString();
        // 创建 session 记录（currentQuestion 留空，批量模式不逐题跟踪）
        sessions.put(sessionId, new CodingSession(
            sessionId, userId, topic, difficulty, "选择题",
            questions.size(), 0, "", "", 0, 0, Instant.now(), excludedTopics));

        QuizPayload payload = new QuizPayload(sessionId, topic, difficulty, questions.size(), questions);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agent", "CodingPracticeAgent");
        result.put("status", "batch_quiz");
        result.put("sessionId", sessionId);
        result.put("quizPayload", payload);
        return result;
    }

    /**
     * 批量提交选择题结果，计算总分并异步更新学习画像。
     */
    public Map<String, Object> submitBatchQuizResults(Map<String, Object> input) {
        String sessionId = text(input, "sessionId");
        CodingSession session = sessions.get(sessionId);
        if (session == null) {
            return Map.of("agent", "CodingPracticeAgent", "status", "not_found", "message", "Session已失效");
        }

        Object resultsObj = input.get("results");
        if (!(resultsObj instanceof List<?> resultsList)) {
            return Map.of("agent", "CodingPracticeAgent", "status", "bad_request", "message", "results 不能为空");
        }

        int totalCorrect = 0;
        int totalCount = resultsList.size();
        for (Object item : resultsList) {
            if (item instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("isCorrect"))) {
                totalCorrect++;
            }
        }

        int score = totalCount > 0 ? (totalCorrect * 100 / totalCount) : 0;

        // 异步更新学习画像
        final int finalScore = score;
        final int finalTotalCorrect = totalCorrect;
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                learningProfileAgent.upsertEvent(new io.github.imzmq.interview.learning.application.LearningEvent(
                    "batch-quiz-" + sessionId,
                    session.userId(),
                    io.github.imzmq.interview.learning.application.LearningSource.CODING,
                    session.topic(),
                    finalScore,
                    finalScore < 60 ? List.of("选择题正确率偏低", "需加强" + session.topic()) : List.of(),
                    finalScore >= 80 ? List.of(session.topic() + "选择题掌握良好") : List.of(),
                    "批量选择题: " + finalTotalCorrect + "/" + totalCount + " 正确",
                    Instant.now()
                ));
            } catch (Exception e) {
                logger.warn("批量选择题画像更新失败: {}", e.getMessage());
            }
        }, profileUpdateExecutor);

        // 清理 session
        sessions.remove(sessionId);

        return Map.of(
            "agent", "CodingPracticeAgent",
            "status", "batch_quiz_submitted",
            "score", score,
            "totalCorrect", totalCorrect,
            "totalQuestions", totalCount
        );
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
                logger.debug("异步更新学习画像开始");
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
                logger.debug("异步更新学习画像完成");
            } catch (Exception e) {
                logger.warn("异步更新学习画像失败: {}", e.getMessage());
            }
        }, profileUpdateExecutor);

        // 清空当前题目，等待下一次请求生成新题目
        CodingSession updatedSession = new CodingSession(
            session.sessionId(), session.userId(), session.topic(), session.difficulty(), session.type(),
            session.totalQuestions(), session.currentQuestionIndex(), "", "", attempts, bestScore, session.createdAt(), session.excludedTopics()
        );

        if (isScenarioType(session.type())) {
            CodingSession scenarioUpdatedSession = new CodingSession(
                    session.sessionId(), session.userId(), session.topic(), session.difficulty(), session.type(),
                    session.totalQuestions(), session.currentQuestionIndex(), "", session.currentCardId(), attempts, bestScore, session.createdAt(), session.excludedTopics()
            );
            sessions.put(session.sessionId(), scenarioUpdatedSession);
            String referenceAnswer = buildScenarioReferenceAnswer(session.currentQuestion(), session.topic(), finalAssessment);
            ScenarioPayload payload = buildScenarioPayload(
                    session,
                    session.currentQuestion(),
                    session.currentCardId(),
                    answer,
                    true,
                    false,
                    score,
                    assessment.feedback(),
                    referenceAnswer,
                    assessment.nextHint(),
                    session.currentQuestionIndex() < session.totalQuestions()
            );
            return Map.of(
                    "agent", "CodingPracticeAgent",
                    "status", "scenario_evaluated",
                    "sessionId", session.sessionId(),
                    "scenarioPayload", payload
            );
        }
        if (isFillType(session.type())) {
            CodingSession fillUpdatedSession = new CodingSession(
                    session.sessionId(), session.userId(), session.topic(), session.difficulty(), session.type(),
                    session.totalQuestions(), session.currentQuestionIndex(), "", session.currentCardId(), attempts, bestScore, session.createdAt(), session.excludedTopics()
            );
            sessions.put(session.sessionId(), fillUpdatedSession);
            String referenceAnswer = buildFillReferenceAnswer(session.currentQuestion(), session.topic(), finalAssessment);
            FillPayload payload = buildFillPayload(
                    session,
                    session.currentQuestion(),
                    session.currentCardId(),
                    answer,
                    true,
                    false,
                    score,
                    assessment.feedback(),
                    referenceAnswer,
                    assessment.nextHint(),
                    session.currentQuestionIndex() < session.totalQuestions()
            );
            return Map.of(
                    "agent", "CodingPracticeAgent",
                    "status", "fill_evaluated",
                    "sessionId", session.sessionId(),
                    "fillPayload", payload
            );
        }
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

    private Map<String, Object> submitScenarioCard(Map<String, Object> input) {
        String sessionId = text(input, "sessionId");
        String cardId = text(input, "cardId");
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
                    "message", "Session已失效"
            );
        }
        if (!isScenarioType(session.type())) {
            return Map.of(
                    "agent", "CodingPracticeAgent",
                    "status", "bad_request",
                    "message", "当前 session 不是场景题"
            );
        }
        if (session.currentQuestion() == null || session.currentQuestion().isBlank()) {
            return Map.of(
                    "agent", "CodingPracticeAgent",
                    "status", "bad_request",
                    "message", "当前场景题已提交或不存在待作答题目"
            );
        }
        if (cardId.isBlank() || !cardId.equals(session.currentCardId())) {
            return Map.of(
                    "agent", "CodingPracticeAgent",
                    "status", "bad_request",
                    "message", "场景题卡片已失效，请刷新后重试"
            );
        }
        return evaluateChatAnswer(session, answer);
    }

    private Map<String, Object> nextScenarioCard(Map<String, Object> input) {
        String sessionId = text(input, "sessionId");
        String cardId = text(input, "cardId");
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
                    "message", "Session已失效"
            );
        }
        if (!isScenarioType(session.type())) {
            return Map.of(
                    "agent", "CodingPracticeAgent",
                    "status", "bad_request",
                    "message", "当前 session 不是场景题"
            );
        }
        if (session.currentQuestion() != null && !session.currentQuestion().isBlank()) {
            return Map.of(
                    "agent", "CodingPracticeAgent",
                    "status", "bad_request",
                    "message", "请先提交当前场景题答案"
            );
        }
        if (cardId.isBlank() || !cardId.equals(session.currentCardId())) {
            return Map.of(
                    "agent", "CodingPracticeAgent",
                    "status", "bad_request",
                    "message", "这张场景题卡片已经不是当前可继续的题目"
            );
        }
        return generateNextChatQuestion(session);
    }

    private Map<String, Object> submitFillCard(Map<String, Object> input) {
        String sessionId = text(input, "sessionId");
        String cardId = text(input, "cardId");
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
                    "message", "Session已失效"
            );
        }
        if (!isFillType(session.type())) {
            return Map.of(
                    "agent", "CodingPracticeAgent",
                    "status", "bad_request",
                    "message", "当前 session 不是填空题"
            );
        }
        if (session.currentQuestion() == null || session.currentQuestion().isBlank()) {
            return Map.of(
                    "agent", "CodingPracticeAgent",
                    "status", "bad_request",
                    "message", "当前填空题已提交或不存在待作答题目"
            );
        }
        if (cardId.isBlank() || !cardId.equals(session.currentCardId())) {
            return Map.of(
                    "agent", "CodingPracticeAgent",
                    "status", "bad_request",
                    "message", "填空题卡片已失效，请刷新后重试"
            );
        }
        return evaluateChatAnswer(session, answer);
    }

    private Map<String, Object> nextFillCard(Map<String, Object> input) {
        String sessionId = text(input, "sessionId");
        String cardId = text(input, "cardId");
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
                    "message", "Session已失效"
            );
        }
        if (!isFillType(session.type())) {
            return Map.of(
                    "agent", "CodingPracticeAgent",
                    "status", "bad_request",
                    "message", "当前 session 不是填空题"
            );
        }
        if (session.currentQuestion() != null && !session.currentQuestion().isBlank()) {
            return Map.of(
                    "agent", "CodingPracticeAgent",
                    "status", "bad_request",
                    "message", "请先提交当前填空题答案"
            );
        }
        if (cardId.isBlank() || !cardId.equals(session.currentCardId())) {
            return Map.of(
                    "agent", "CodingPracticeAgent",
                    "status", "bad_request",
                    "message", "这张填空题卡片已经不是当前可继续的题目"
            );
        }
        return generateNextChatQuestion(session);
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
        String query = text(input, "query"); // 用户原始输入
        List<String> excludedTopics = readTextList(input, "excludedTopics");
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
        // 把原始 query 也传给 normalizePracticeType，确保"来三道选择题"中的"选择"能被检测到
        String normalizedType = normalizePracticeType(type, normalizedTopic + " " + query);

        String resolvedProfileSnapshot = profileSnapshot.isBlank()
                ? learningProfileAgent.snapshotForPrompt(userId, normalizedTopic)
                : profileSnapshot;

        // 从 payload 读取题目数量
        int totalQuestions = 1;
        Object countVal = input.get("count");
        if (countVal instanceof Number n && n.intValue() > 0) {
            totalQuestions = Math.min(n.intValue(), 10);
        } else if (countVal instanceof String s && !s.isBlank()) {
            try { totalQuestions = Math.min(Integer.parseInt(s.trim()), 10); } catch (NumberFormatException ignored) {}
        }
        // 兜底：如果上游未提取 count，从用户原始输入中正则提取
        if (totalQuestions <= 1 && !query.isBlank()) {
            int extracted = extractCountFromText(query);
            if (extracted > 1) {
                totalQuestions = Math.min(extracted, 10);
            }
        }

        logger.info("[startPractice] query='{}', type='{}', normalizedType='{}', count={}, totalQuestions={}",
                query, type, normalizedType, countVal, totalQuestions);

        // 选择题 + 多题 → 走批量交互式模式（提前判断，避免浪费单题 LLM 调用）
        if (normalizedType.contains("选择") && totalQuestions > 1) {
            return handleBatchQuiz(userId, normalizedTopic, normalizedDifficulty, totalQuestions, resolvedProfileSnapshot, excludedTopics);
        }

        String sessionId = UUID.randomUUID().toString();
        // 调用 RAG 生成题目，将类型融入主题描述中以获得更准确的题目生成
        String ragTopic = buildTopicWithType(normalizedTopic, normalizedType);
        String question = ragService.generateCodingQuestion(ragTopic, normalizedDifficulty, resolvedProfileSnapshot, excludedTopics);
        if (question == null || question.isBlank()) {
            question = buildQuestion(normalizedTopic, normalizedDifficulty, normalizedType) + buildExclusionSuffix(excludedTopics) + " (" + normalizedType + ")";
        }

        // 保存会话并返回结果
        sessions.put(sessionId, new CodingSession(sessionId, userId, normalizedTopic, normalizedDifficulty, normalizedType, totalQuestions, 1, question, "", 0, 0, Instant.now(), excludedTopics));
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
                buildTopicWithType(session.topic(), session.type()),
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
                session.currentCardId(),
                attempts,
                bestScore,
                session.createdAt(),
                session.excludedTopics()
        ));
        
        // 将练习结果异步沉淀为学习事件写入画像，使用自定义线程池 profileUpdateExecutor
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                logger.debug("异步更新学习画像开始");
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
                logger.debug("异步更新学习画像完成");
            } catch (Exception e) {
                logger.warn("异步更新学习画像失败: {}", e.getMessage());
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
        String nextQuestion = buildQuestion(topic, score >= 80 ? "hard" : score >= 60 ? "medium" : "easy", normalizePracticeType("", topic));
        return new RAGService.CodingAssessment(score, feedback(score), hint(score, topic), nextQuestion);
    }

    /**
     * 构建题目文本模板。
     */
    private String buildQuestion(String topic, String difficulty, String type) {
        String normalizedType = normalizePracticeType(type, topic);
        if (normalizedType.contains("选择")) {
            return "请生成一道" + difficulty + "难度的「" + topic + "」选择题，包含 4 个选项，不要输出答案。";
        }
        if (normalizedType.contains("填空")) {
            return "请生成一道" + difficulty + "难度的「" + topic + "」填空题，给出题干与必要约束，不要输出答案。";
        }
        if (normalizedType.contains("场景")) {
            return "请生成一道" + difficulty + "难度的「" + topic + "」场景题，聚焦真实工程情境，不要输出答案。";
        }
        return "请实现一道" + difficulty + "难度的「" + topic + "」算法题，要求说明思路、复杂度与边界条件。";
    }

    private String buildTopicWithType(String topic, String type) {
        String normalizedTopic = topic == null ? "" : topic.trim();
        String normalizedType = normalizePracticeType(type, normalizedTopic);
        if (normalizedType.isBlank()) {
            return normalizedTopic;
        }
        if (normalizedTopic.isBlank()) {
            return normalizedType;
        }
        return normalizedTopic + "（" + normalizedType + "）";
    }

    private String buildExclusionSuffix(List<String> excludedTopics) {
        if (excludedTopics == null || excludedTopics.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n不要出以下知识点的题目：");
        for (int i = 0; i < excludedTopics.size(); i++) {
            if (i > 0) sb.append("、");
            sb.append(excludedTopics.get(i));
        }
        sb.append("。");
        return sb.toString();
    }

    private String normalizePracticeType(String rawType, String userText) {
        String source = ((rawType == null ? "" : rawType) + " " + (userText == null ? "" : userText)).toLowerCase();
        if (source.contains("选择") || source.contains("choice") || source.contains("单选") || source.contains("多选")) {
            return "选择题";
        }
        if (source.contains("填空") || source.contains("fill") || source.contains("补全")) {
            return "填空题";
        }
        if (source.contains("场景") || source.contains("scenario")) {
            return "场景题";
        }
        if (source.contains("算法") || source.contains("algorithm") || source.contains("编程")) {
            return "算法题";
        }
        return "选择题";
    }

    private boolean isScenarioType(String type) {
        return type != null && type.contains("场景");
    }

    private boolean isFillType(String type) {
        return type != null && type.contains("填空");
    }

    private ScenarioPayload buildScenarioPayload(CodingSession session,
                                                 String stem,
                                                 String cardId,
                                                 String userAnswer,
                                                 boolean submitted,
                                                 boolean evaluating,
                                                 Integer score,
                                                 String feedback,
                                                 String referenceAnswer,
                                                 String nextHint,
                                                 boolean canContinue) {
        return new ScenarioPayload(
                cardId == null ? "" : cardId,
                session.sessionId(),
                session.topic(),
                session.difficulty(),
                session.type(),
                stem == null ? "" : stem,
                userAnswer == null ? "" : userAnswer,
                submitted,
                evaluating,
                score,
                feedback == null ? "" : feedback,
                referenceAnswer == null ? "" : referenceAnswer,
                nextHint == null ? "" : nextHint,
                session.currentQuestionIndex() + "/" + session.totalQuestions(),
                session.currentQuestionIndex() >= session.totalQuestions(),
                canContinue
        );
    }

    private String buildScenarioReferenceAnswer(String question,
                                                String topic,
                                                RAGService.CodingAssessment assessment) {
        List<String> sections = new ArrayList<>();
        sections.add("高质量回答至少应先明确目标、约束和成功标准。");
        sections.add("然后给出核心方案、关键流程以及主要技术取舍。");
        sections.add("最后补充边界情况、失败兜底、监控告警和可观测性。");
        if (topic != null && !topic.isBlank()) {
            sections.add("本题建议重点围绕「" + topic + "」展开。");
        }
        if (assessment != null && assessment.nextHint() != null && !assessment.nextHint().isBlank()) {
            sections.add("建议补充：" + assessment.nextHint().trim());
        } else if (question != null && !question.isBlank()) {
            sections.add("回答时要确保每个关键环节都能回扣题干中的真实工程场景。");
        }
        return String.join("\n", sections);
    }

    private FillPayload buildFillPayload(CodingSession session,
                                         String stem,
                                         String cardId,
                                         String userAnswer,
                                         boolean submitted,
                                         boolean evaluating,
                                         Integer score,
                                         String feedback,
                                         String referenceAnswer,
                                         String nextHint,
                                         boolean canContinue) {
        return new FillPayload(
                cardId == null ? "" : cardId,
                session.sessionId(),
                session.topic(),
                session.difficulty(),
                session.type(),
                stem == null ? "" : stem,
                userAnswer == null ? "" : userAnswer,
                submitted,
                evaluating,
                score,
                feedback == null ? "" : feedback,
                referenceAnswer == null ? "" : referenceAnswer,
                nextHint == null ? "" : nextHint,
                session.currentQuestionIndex() + "/" + session.totalQuestions(),
                session.currentQuestionIndex() >= session.totalQuestions(),
                canContinue
        );
    }

    private String buildFillReferenceAnswer(String question,
                                            String topic,
                                            RAGService.CodingAssessment assessment) {
        List<String> sections = new ArrayList<>();
        sections.add("参考答案应直接补全题干中的关键空缺，并确保语义与上下文一致。");
        sections.add("如果题目涉及 SQL、代码或配置，答案需要保持语法完整。");
        if (topic != null && !topic.isBlank()) {
            sections.add("本题重点知识点来自「" + topic + "」。");
        }
        if (assessment != null && assessment.nextHint() != null && !assessment.nextHint().isBlank()) {
            sections.add("建议补充：" + assessment.nextHint().trim());
        } else if (question != null && !question.isBlank()) {
            sections.add("检查是否遗漏关键字、边界条件或必要约束。");
        }
        return String.join("\n", sections);
    }

    /**
     * 从用户原始文本中提取题目数量。
     * 支持阿拉伯数字（"来3道"）和常见中文数字（"来三道"）。
     */
    private static final java.util.regex.Pattern COUNT_PATTERN =
            java.util.regex.Pattern.compile("([\\d零一二两三四五六七八九十百千]+)\\s*[道题个]");

    static int extractCountFromText(String text) {
        if (text == null || text.isBlank()) return 0;
        java.util.regex.Matcher m = COUNT_PATTERN.matcher(text);
        if (!m.find()) return 0;
        String raw = m.group(1);
        // 先尝试阿拉伯数字
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {}
        // 中文数字转换
        return chineseToInt(raw);
    }

    private static int chineseToInt(String cn) {
        if (cn == null || cn.isEmpty()) return 0;
        // 单字快速路径
        if (cn.length() == 1) return singleCnDigit(cn.charAt(0));
        // 组合数字: "十二"=12, "二十"=20, "二十三"=23, "一百"=100 ...
        int result = 0, current = 0;
        for (char c : cn.toCharArray()) {
            int digit = singleCnDigit(c);
            if (c == '十') {
                result += (current == 0 ? 1 : current) * 10;
                current = 0;
            } else if (c == '百') {
                result += (current == 0 ? 1 : current) * 100;
                current = 0;
            } else if (c == '千') {
                result += (current == 0 ? 1 : current) * 1000;
                current = 0;
            } else {
                current = digit;
            }
        }
        return result + current;
    }

    private static int singleCnDigit(char c) {
        return switch (c) {
            case '零' -> 0; case '一' -> 1; case '二', '两' -> 2; case '三' -> 3;
            case '四' -> 4; case '五' -> 5; case '六' -> 6; case '七' -> 7;
            case '八' -> 8; case '九' -> 9; case '十' -> 10;
            default -> 0;
        };
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

    @SuppressWarnings("unchecked")
    private List<String> readTextList(Map<String, Object> input, String key) {
        if (input == null) return List.of();
        Object raw = input.get(key);
        if (raw instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null && !item.toString().isBlank()) {
                    result.add(item.toString().trim());
                }
            }
            return result;
        }
        return List.of();
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
     * 批量选择题数据契约。
     */
    public record QuizPayload(
            String sessionId,
            String topic,
            String difficulty,
            int totalQuestions,
            List<QuizQuestion> questions
    ) {}

    /**
     * 单个选择题数据。
     */
    public record QuizQuestion(
            int index,
            String stem,
            List<String> options,     // ["A. xxx", "B. xxx", "C. xxx", "D. xxx"]
            String correctAnswer,     // "A"/"B"/"C"/"D"
            String explanation
    ) {}

    public record ScenarioPayload(
            String cardId,
            String sessionId,
            String topic,
            String difficulty,
            String type,
            String stem,
            String userAnswer,
            boolean submitted,
            boolean evaluating,
            Integer score,
            String feedback,
            String referenceAnswer,
            String nextHint,
            String progress,
            boolean isLast,
            boolean canContinue
    ) {}

    public record FillPayload(
            String cardId,
            String sessionId,
            String topic,
            String difficulty,
            String type,
            String stem,
            String userAnswer,
            boolean submitted,
            boolean evaluating,
            Integer score,
            String feedback,
            String referenceAnswer,
            String nextHint,
            String progress,
            boolean isLast,
            boolean canContinue
    ) {}

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
            String currentCardId,
            int attempts,
            int bestScore,
            Instant createdAt,
            List<String> excludedTopics
    ) {
    }
}





