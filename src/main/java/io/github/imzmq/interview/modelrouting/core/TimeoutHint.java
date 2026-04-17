package io.github.imzmq.interview.modelrouting.core;

public enum TimeoutHint {
    FAST(5000, 30000),
    NORMAL(8000, 60000),
    SLOW(15000, 120000);

    private final long firstTokenTimeoutMs;
    private final long totalResponseTimeoutMs;

    TimeoutHint(long firstTokenTimeoutMs, long totalResponseTimeoutMs) {
        this.firstTokenTimeoutMs = firstTokenTimeoutMs;
        this.totalResponseTimeoutMs = totalResponseTimeoutMs;
    }

    public long getFirstTokenTimeoutMs() {
        return firstTokenTimeoutMs;
    }

    public long getTotalResponseTimeoutMs() {
        return totalResponseTimeoutMs;
    }
}

