package io.github.imzmq.interview.agent.application.contract.execution.dynamic;

import java.util.List;

public record CandidateDynamicTaskPlan(String schemaVersion, List<CandidateDynamicTaskNode> nodes) {
    public CandidateDynamicTaskPlan {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }
}
