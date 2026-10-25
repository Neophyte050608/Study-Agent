package io.github.imzmq.interview.observability.application.contract.query;

import io.github.imzmq.interview.observability.application.contract.trace.TraceNodeDetails;
import io.github.imzmq.interview.observability.application.contract.trace.TraceNodeMetrics;

import java.time.Instant;

/** Immutable serialized view of one trace node. */
public record TraceNodeView(
        String nodeId,
        String parentNodeId,
        String nodeType,
        String nodeName,
        Instant startTime,
        Instant endTime,
        String inputSummary,
        String outputSummary,
        String errorSummary,
        TraceNodeMetrics metrics,
        TraceNodeDetails details,
        String status,
        long durationMs
) {
}
