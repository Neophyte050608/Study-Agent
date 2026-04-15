package com.example.interview.skill;

public class SkillPolicyException extends RuntimeException {

    private final boolean retryable;

    public SkillPolicyException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public SkillPolicyException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
