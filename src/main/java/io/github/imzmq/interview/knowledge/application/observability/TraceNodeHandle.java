package io.github.imzmq.interview.knowledge.application.observability;

public record TraceNodeHandle(
        String traceId,
        String nodeId,
        String parentNodeId,
        TraceNodeDefinition definition
) {
}





