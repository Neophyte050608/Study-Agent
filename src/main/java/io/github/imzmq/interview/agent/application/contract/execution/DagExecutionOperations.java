package io.github.imzmq.interview.agent.application.contract.execution;

import java.time.Duration;
import java.util.List;

/** Request-scoped fixed DAG execution, not a durable workflow. */
public interface DagExecutionOperations {
    DagExecutionResult execute(List<DagNode> nodes, Duration deadline);
}
