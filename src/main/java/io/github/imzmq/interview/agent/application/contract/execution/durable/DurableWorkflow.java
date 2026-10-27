package io.github.imzmq.interview.agent.application.contract.execution.durable;

import io.github.imzmq.interview.agent.application.contract.execution.*;
import java.util.List;

/** Owner supplies a versioned fixed graph and explicit JSON codecs. No Java class names are persisted. */
public interface DurableWorkflow {
    String type();
    int version();
    List<DagNode> nodes(String owner, String inputJson);
    String encodeOutput(String nodeId, Object output);
    Object decodeOutput(String nodeId, String outputJson);
    WorkflowOutcome outcome(DagExecutionResult result);
    record WorkflowOutcome(String status, String resultJson) { }
}
