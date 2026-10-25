package io.github.imzmq.interview.observability.application.contract.trace;

/** Structured counters attached to a trace node. */
public record TraceNodeMetrics(Integer retrievedDocs, Boolean webFallbackUsed) {
}
