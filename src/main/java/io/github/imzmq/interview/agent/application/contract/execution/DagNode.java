package io.github.imzmq.interview.agent.application.contract.execution;

import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.function.Function;

/** Dependencies express waiting; requireSuccessfulDependencies additionally forbids failed inputs. */
public record DagNode(String id, Set<String> dependencies, boolean requireSuccessfulDependencies,
                      Function<Map<String, DagNodeResult>, Object> action) {
    public DagNode {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Node ID is required");
        dependencies = Set.copyOf(dependencies);
        Objects.requireNonNull(action, "Node action is required");
    }
}
