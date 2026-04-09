package com.example.interview.service.interview;

import java.util.List;

public record InterviewAnswerView(
        int score,
        String feedback,
        String nextQuestion,
        double averageScore,
        boolean finished,
        int answeredCount,
        int totalQuestions,
        String difficultyLevel,
        String followUpState,
        double topicMastery,
        int accuracy,
        int logic,
        int depth,
        int boundary,
        String deductions,
        List<String> citations,
        List<String> conflicts
) {
}
