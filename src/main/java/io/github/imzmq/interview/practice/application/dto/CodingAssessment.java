package io.github.imzmq.interview.practice.application.dto;

public record CodingAssessment(
        int score,
        String feedback,
        String nextHint,
        String nextQuestion
) {
}
