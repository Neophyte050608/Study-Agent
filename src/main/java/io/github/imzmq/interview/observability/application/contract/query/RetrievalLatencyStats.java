package io.github.imzmq.interview.observability.application.contract.query;

/** Retrieval-node latency percentiles for a normalized observation window. */
public record RetrievalLatencyStats(
        long p95LatencyMs,
        long p99LatencyMs,
        int sampleSize,
        String windowMode,
        Integer windowLimit,
        Integer windowHours
) {
}
