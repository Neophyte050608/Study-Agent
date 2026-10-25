package io.github.imzmq.interview.observability.application.contract.query;

import java.time.Instant;
import java.util.List;

/** Immutable summary exposed by the observability module. */
public record TraceSummary(
        String traceId,
        String traceStatus,
        Long businessDurationMs,
        Long connectionDurationMs,
        Long firstTokenMs,
        Long streamDispatchMs,
        Integer retrievedDocCount,
        Integer displayedDocCount,
        Integer fallbackCount,
        Integer failedNodeCount,
        Boolean slowTrace,
        List<String> riskTags,
        Integer riskCount,
        Integer nodeCount,
        Integer retrievalNodeCount,
        Instant startedAt,
        Instant endedAt,
        Integer followUpCount
) {
    public TraceSummary {
        riskTags = riskTags == null ? List.of() : List.copyOf(riskTags);
    }

    public TraceSummary(
            String traceId, String traceStatus, Long businessDurationMs,
            Long connectionDurationMs, Long firstTokenMs, Long streamDispatchMs,
            Integer retrievedDocCount, Integer displayedDocCount,
            Integer fallbackCount, Integer failedNodeCount, Boolean slowTrace,
            List<String> riskTags, Integer riskCount, Integer nodeCount,
            Integer retrievalNodeCount, Instant startedAt, Instant endedAt
    ) {
        this(traceId, traceStatus, businessDurationMs, connectionDurationMs,
                firstTokenMs, streamDispatchMs, retrievedDocCount, displayedDocCount,
                fallbackCount, failedNodeCount, slowTrace, riskTags, riskCount,
                nodeCount, retrievalNodeCount, startedAt, endedAt, null);
    }
}
