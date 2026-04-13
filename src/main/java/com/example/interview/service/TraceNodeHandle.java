package com.example.interview.service;

public record TraceNodeHandle(
        String traceId,
        String nodeId,
        String parentNodeId,
        TraceNodeDefinition definition
) {
}
