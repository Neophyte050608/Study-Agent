package io.github.imzmq.interview.practice.application.dto;

import java.util.List;

public record QuizQuestion(
        int index,
        String stem,
        List<String> options,
        String correctAnswer,
        String explanation
) {
}
