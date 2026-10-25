package io.github.imzmq.interview.interview.application.contract.result;

/** Public task result emitted after a new interview session has been created. */
public record InterviewSessionStarted(
        String sessionId,
        String currentQuestion,
        int totalQuestions
) {
}
