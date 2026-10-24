package io.github.imzmq.interview.observability.application.trace;

public record TraceNodeHandle(
        String traceId,
        String nodeId,
        String parentNodeId,
        TraceNodeDefinition definition
) {
}





