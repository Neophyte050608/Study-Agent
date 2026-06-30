package io.github.imzmq.interview.agent.application.context;

import io.github.imzmq.interview.knowledge.application.RAGService;

public final class InterviewContextAttributes {
    public static final String PROFILE_SNAPSHOT = "interview.profileSnapshot";
    public static final String STRATEGY_HINT = "interview.strategyHint";
    public static final String TOPIC = "interview.topic";
    public static final String QUESTION = "interview.question";
    public static final String USER_ANSWER = "interview.userAnswer";
    public static final String DIFFICULTY_LEVEL = "interview.difficultyLevel";
    public static final String FOLLOW_UP_STATE = "interview.followUpState";
    public static final String TOPIC_MASTERY = "interview.topicMastery";
    public static final String KNOWLEDGE_PACKET = "interview.knowledgePacket";
    public static final String CURRENT_STAGE = "interview.currentStage";
    public static final String NEXT_STAGE = "interview.nextStage";
    public static final String ANSWERED_COUNT = "interview.answeredCount";
    public static final String TOTAL_QUESTIONS = "interview.totalQuestions";

    private InterviewContextAttributes() {
    }

    public static String text(AgentContextQuery query, String key) {
        Object value = query == null ? null : query.attribute(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    public static double number(AgentContextQuery query, String key, double defaultValue) {
        Object value = query == null ? null : query.attribute(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    public static RAGService.KnowledgePacket packet(AgentContextQuery query) {
        Object value = query == null ? null : query.attribute(KNOWLEDGE_PACKET);
        return value instanceof RAGService.KnowledgePacket packet ? packet : null;
    }
}
