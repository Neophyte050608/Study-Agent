package io.github.imzmq.interview.feedback.domain;

import java.util.UUID;

public record FeedbackEvent(
        String feedbackId,
        String traceId,
        String messageId,
        String userId,
        FeedbackType type,
        String scene,
        String queryText
) {
    public enum FeedbackType {
        THUMBS_UP, THUMBS_DOWN, COPY, REGENERATE
    }

    public static FeedbackEvent fromRequest(String traceId, String messageId, String userId,
                                            FeedbackType type, String scene, String queryText) {
        return new FeedbackEvent(
                UUID.randomUUID().toString(),
                blankToNull(traceId),
                blankToNull(messageId),
                userId,
                type,
                blankToNull(scene),
                blankToNull(queryText)
        );
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
