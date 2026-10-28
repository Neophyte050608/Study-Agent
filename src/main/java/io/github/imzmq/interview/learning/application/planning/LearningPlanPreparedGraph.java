package io.github.imzmq.interview.learning.application.planning;

import io.github.imzmq.interview.agent.application.contract.execution.DagNode;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskPlan;

import java.util.List;
import java.util.Objects;

record LearningPlanPreparedGraph(
        ValidatedDynamicTaskPlan plan,
        String digest,
        List<DagNode> nodes,
        Source source,
        String fallbackCode) {

    enum Source {
        RULES,
        FIXED_FALLBACK
    }

    LearningPlanPreparedGraph {
        Objects.requireNonNull(plan, "Validated plan is required");
        Objects.requireNonNull(digest, "Plan digest is required");
        nodes = List.copyOf(nodes);
        Objects.requireNonNull(source, "Plan source is required");
        if ((source == Source.RULES) != (fallbackCode == null)) {
            throw new IllegalArgumentException("Invalid prepared plan source");
        }
    }
}
