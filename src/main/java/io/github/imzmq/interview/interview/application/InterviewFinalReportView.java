package io.github.imzmq.interview.interview.application;

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

