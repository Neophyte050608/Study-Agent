package com.example.interview.service.interview;

public record InterviewFinalReportView(
        String summary,
        String incomplete,
        String weak,
        String wrong,
        String obsidianUpdates,
        String nextFocus,
        double averageScore,
        int answeredCount
) {
}
