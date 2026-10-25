package io.github.imzmq.interview.interview.application.evaluation;

/** Model response and token usage produced by the interview-owned evaluation use case. */
public record InterviewEvaluationOutput(String json, int inputTokens, int outputTokens) {
}
