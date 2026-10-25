package io.github.imzmq.interview.observability.application.contract.trace;

public record TraceNodeHandle(
        String traceId,
        String nodeId,
        String parentNodeId,
        TraceNodeDefinition definition
) {
}





