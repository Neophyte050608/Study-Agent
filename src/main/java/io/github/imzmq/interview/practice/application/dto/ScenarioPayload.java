package io.github.imzmq.interview.practice.application.dto;

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
) {
}
