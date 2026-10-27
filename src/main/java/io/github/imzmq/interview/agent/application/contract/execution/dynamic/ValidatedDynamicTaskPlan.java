package io.github.imzmq.interview.agent.application.contract.execution.dynamic;

import java.util.List;

public record ValidatedDynamicTaskPlan(
        String schemaVersion,
        String scenarioId,
        List<ValidatedDynamicTaskNode> nodes,
        int estimatedCost) {
    public ValidatedDynamicTaskPlan {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }
}
