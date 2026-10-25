package io.github.imzmq.interview.observability.application.contract.trace;

/** Cross-module write-side operations for RAG and agent traces. */
public interface RagTraceOperations {

    void startNode(String traceId, String nodeId, String parentNodeId, String nodeType, String nodeName);

    void endNode(String traceId, String nodeId, String inputSummary, String outputSummary, String errorSummary);

    void endNode(
            String traceId,
            String nodeId,
            String inputSummary,
            String outputSummary,
            String errorSummary,
            TraceNodeMetrics metrics
    );

    void endNode(
            String traceId,
            String nodeId,
            String inputSummary,
            String outputSummary,
            String errorSummary,
            TraceNodeMetrics metrics,
            TraceNodeDetails details
    );

    String getActiveRootNodeId(String traceId);
}
