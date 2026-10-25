package io.github.imzmq.interview.observability.application.contract.query;

/** Public trace detail response. */
public record TraceDetailView(TraceView trace, TraceSummary summary) {
}
