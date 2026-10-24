package io.github.imzmq.interview.feedback.application.contract.result;

public record FeedbackMetrics(
        long thumbsUpCount,
        long thumbsDownCount,
        long copyCount
) {
}
