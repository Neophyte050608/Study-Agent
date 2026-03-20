package com.example.interview.agent;

import com.example.interview.service.LearningEvent;
import com.example.interview.service.LearningProfileAgent;
import com.example.interview.service.LearningSource;
import com.example.interview.service.RAGService;
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
    /** 内存中的编程练习会话缓存 */
    private final Map<String, CodingSession> sessions = new ConcurrentHashMap<>();

    public CodingPracticeAgent(RAGService ragService, LearningProfileAgent learningProfileAgent) {
        this.ragService = ragService;
        this.learningProfileAgent = learningProfileAgent;
    }

    /**
     * 执行编程练习相关的操作。
     * 支持的 action 包括：start (开始练习), submit (提交代码), state (查询状态)。
     * 
     * @param input 包含 action 和相关参数的 Map
     * @return 操作结果响应
     */
    @Override
    public Map<String, Object> execute(Map<String, Object> input) {
        String action = text(input, "action").toLowerCase();
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
     * 开始一个新的编程练习。
     * 如果未指定主题，将根据用户画像推荐合适的主题。
     */
    private Map<String, Object> startPractice(Map<String, Object> input) {
        String userId = learningProfileAgent.normalizeUserId(text(input, "userId"));
        String topic = text(input, "topic");
        String difficulty = text(input, "difficulty");
        String profileSnapshot = text(input, "profileSnapshot");
        String recommendedTopic = "";
        
        // 如果没有传入主题，尝试从画像推荐
        if (topic.isBlank()) {
            List<String> recommended = learningProfileAgent.snapshot(userId).recommendedNextCodingTopics();
            if (!recommended.isEmpty()) {
                recommendedTopic = recommended.getFirst();
            }
        }
        
        // 归一化主题、难度和画像快照
        String normalizedTopic = topic.isBlank() ? (recommendedTopic.isBlank() ? "数组与字符串" : recommendedTopic) : topic;
        String normalizedDifficulty = difficulty.isBlank() ? "medium" : difficulty.toLowerCase();
        String resolvedProfileSnapshot = profileSnapshot.isBlank()
                ? learningProfileAgent.snapshotForPrompt(userId, normalizedTopic)
                : profileSnapshot;
        
        String sessionId = UUID.randomUUID().toString();
        // 调用 RAG 生成题目
        String question = ragService.generateCodingQuestion(normalizedTopic, normalizedDifficulty, resolvedProfileSnapshot);
        if (question == null || question.isBlank()) {
            question = buildQuestion(normalizedTopic, normalizedDifficulty);
        }
        
        // 保存会话并返回结果
        sessions.put(sessionId, new CodingSession(sessionId, userId, normalizedTopic, normalizedDifficulty, question, 0, 0, Instant.now()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agent", "CodingPracticeAgent");
        result.put("status", "started");
        result.put("sessionId", sessionId);
        result.put("userId", userId);
        result.put("topic", normalizedTopic);
        result.put("difficulty", normalizedDifficulty);
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
                session.question(),
                answer
        );
        if (assessment == null) {
            assessment = fallbackAssessment(answer, session.topic());
        }
        
        int score = assessment.score();
        int attempts = session.attempts() + 1;
        int bestScore = Math.max(session.bestScore(), score);
        
        // 更新会话状态
        sessions.put(sessionId, new CodingSession(
                session.sessionId(),
                session.userId(),
                session.topic(),
                session.difficulty(),
                session.question(),
                attempts,
                bestScore,
                session.createdAt()
        ));
        
        // 将练习结果作为学习事件写入画像
        learningProfileAgent.upsertEvent(new LearningEvent(
                "coding-" + sessionId + "-" + attempts,
                session.userId(),
                LearningSource.CODING,
                session.topic(),
                score,
                weakPointsByScore(score, assessment.feedback(), assessment.nextHint()),
                familiarPointsByScore(score, assessment.feedback()),
                assessment.feedback(),
                Instant.now()
        ));
        
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
        data.put("question", session.question());
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
            String question,
            int attempts,
            int bestScore,
            Instant createdAt
    ) {
    }
}
