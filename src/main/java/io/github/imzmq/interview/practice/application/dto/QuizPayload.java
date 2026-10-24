package io.github.imzmq.interview.practice.application.dto;

import java.util.List;

public record QuizPayload(
        String sessionId,
        String topic,
        String difficulty,
        int totalQuestions,
        List<QuizQuestion> questions
) {
}
