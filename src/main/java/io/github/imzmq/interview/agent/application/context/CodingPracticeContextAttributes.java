package io.github.imzmq.interview.agent.application.context;

import java.util.List;

public final class CodingPracticeContextAttributes {
    public static final String USER_ID = "codingPractice.userId";
    public static final String TOPIC = "codingPractice.topic";
    public static final String DIFFICULTY = "codingPractice.difficulty";
    public static final String QUESTION_TYPE = "codingPractice.questionType";
    public static final String COUNT = "codingPractice.count";
    public static final String EXCLUDED_TOPICS = "codingPractice.excludedTopics";
    public static final String PROFILE_SNAPSHOT = "codingPractice.profileSnapshot";

    private CodingPracticeContextAttributes() {
    }

    public static String text(AgentContextQuery query, String key) {
        Object value = query == null ? null : query.attribute(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    public static int integer(AgentContextQuery query, String key, int defaultValue) {
        Object value = query == null ? null : query.attribute(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    public static List<String> stringList(AgentContextQuery query, String key) {
        Object value = query == null ? null : query.attribute(key);
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(item -> item == null ? "" : String.valueOf(item).trim())
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text.trim());
        }
        return List.of();
    }
}
