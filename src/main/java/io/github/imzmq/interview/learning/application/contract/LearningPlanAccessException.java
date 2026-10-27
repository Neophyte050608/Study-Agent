package io.github.imzmq.interview.learning.application.contract;

/** Fixed public reasons. Never includes a database/SDK exception message or cause. */
public final class LearningPlanAccessException extends RuntimeException {
    private final Code code;

    public LearningPlanAccessException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        INVALID_INPUT, INVALID_PREVIEW_TOPIC, INVALID_PREVIEW_REQUEST, UNAUTHENTICATED,
        EXECUTION_NOT_FOUND, IDEMPOTENCY_CONFLICT, REVISION_CONFLICT, EXECUTION_NOT_RETRYABLE,
        EXECUTION_NOT_TERMINAL, RETRY_ROOT_NOT_FAILED, ATTEMPT_BUDGET_EXHAUSTED,
        EXECUTION_DISABLED, EXECUTION_UNAVAILABLE, INVALID_EXECUTION_RESULT, INTERNAL_ERROR
    }
}
