package io.github.imzmq.interview.feedback.application.contract;

import io.github.imzmq.interview.feedback.application.contract.result.FeedbackMetrics;

import java.time.LocalDateTime;

public interface FeedbackMetricsQuery {

    FeedbackMetrics summarize(LocalDateTime from, LocalDateTime to);
}
