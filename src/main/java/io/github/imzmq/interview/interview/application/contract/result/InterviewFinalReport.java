package io.github.imzmq.interview.interview.application.contract.result;

public record InterviewFinalReport(
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
