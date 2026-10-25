package io.github.imzmq.interview.observability.application.contract.query;

import java.time.Instant;
import java.util.List;

/** Immutable serialized trace view that does not expose the mutable runtime model. */
public record TraceView(
        String traceId,
        Instant startTime,
        List<TraceNodeView> nodes,
        long durationMs,
        Instant effectiveStartTime,
        Instant effectiveEndTime
) {
    public TraceView {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    public long getDurationMs() {
        return durationMs;
    }

    public Instant getEffectiveStartTime() {
        return effectiveStartTime;
    }

    public Instant getEffectiveEndTime() {
        return effectiveEndTime;
    }
}
