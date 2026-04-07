package com.example.interview.service;

/**
 * 本地知识图检索异常。
 */
public class LocalGraphRetrievalException extends RuntimeException {

    private final LocalGraphFailureReason failureReason;

    public LocalGraphRetrievalException(LocalGraphFailureReason failureReason, String message) {
        super(message);
        this.failureReason = failureReason;
    }

    public LocalGraphRetrievalException(LocalGraphFailureReason failureReason, String message, Throwable cause) {
        super(message, cause);
        this.failureReason = failureReason;
    }

    public LocalGraphFailureReason getFailureReason() {
        return failureReason;
    }
}
