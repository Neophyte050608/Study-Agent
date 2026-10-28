package io.github.imzmq.interview.agent.application.contract.execution.durable;

import io.github.imzmq.interview.agent.application.contract.execution.DagExecutionResult;
import io.github.imzmq.interview.agent.application.contract.execution.DagNode;

import java.util.List;

/** Owner prepares a versioned definition once and restores executable nodes from that definition. */
public interface DurableWorkflow {
    String type();
    int version();
    Prepared prepare(String owner, String inputJson);
    List<DagNode> restore(String owner, String inputJson, String definitionJson);
    String encodeOutput(String nodeId, Object output);
    Object decodeOutput(String nodeId, String outputJson);
    WorkflowOutcome outcome(DagExecutionResult result);

    record Prepared(String definitionJson, List<DagNode> nodes) {
        public Prepared {
            if (definitionJson == null || definitionJson.isBlank() || nodes == null) {
                throw new IllegalArgumentException("WORKFLOW_DEFINITION_INVALID");
            }
            nodes = List.copyOf(nodes);
        }
    }

    record WorkflowOutcome(String status, String resultJson) { }
}
