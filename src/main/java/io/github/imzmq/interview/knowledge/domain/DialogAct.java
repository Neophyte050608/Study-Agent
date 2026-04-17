package io.github.imzmq.interview.knowledge.domain;

public enum DialogAct {
    FOLLOW_UP,
    CLARIFICATION,
    NEW_QUESTION,
    COMPARISON,
    RETURN,
    SUMMARY;

    public static DialogAct fromString(String value) {
        if (value == null || value.isBlank()) {
            return FOLLOW_UP;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return FOLLOW_UP;
        }
    }
}


