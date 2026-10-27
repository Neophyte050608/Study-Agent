package io.github.imzmq.interview.agent.domain.execution;

import java.util.*;

/** Retry invalidates the complete descendant closure, never an unrelated successful sibling. */
public final class DurableRetrySelection {
    private DurableRetrySelection() { }
    public static Set<String> select(Map<String, Set<String>> dependencies, Map<String, String> states,
                                     Map<String, Integer> attempts, Set<String> selected, int maximum) {
        new DagStructure(new ArrayList<>(dependencies.keySet()), dependencies);
        Set<String> roots = new HashSet<>(selected);
        if (roots.isEmpty()) states.forEach((id, state) -> { if ("FAILED".equals(state)) roots.add(id); });
        if (roots.isEmpty() || roots.stream().anyMatch(id -> !"FAILED".equals(states.get(id)))) {
            throw new IllegalArgumentException("RETRY_ROOT_NOT_FAILED");
        }
        Set<String> closure = new HashSet<>(roots);
        boolean changed;
        do {
            changed = false;
            for (var entry : dependencies.entrySet()) {
                if (!Collections.disjoint(entry.getValue(), closure)) changed |= closure.add(entry.getKey());
            }
        } while (changed);
        if (closure.stream().anyMatch(id -> attempts.getOrDefault(id, 0) >= maximum)) {
            throw new IllegalArgumentException("ATTEMPT_BUDGET_EXHAUSTED");
        }
        return Set.copyOf(closure);
    }
}
