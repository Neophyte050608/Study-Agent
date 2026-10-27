package io.github.imzmq.interview.agent.domain.execution;

import java.util.*;

/** Immutable validated dependency structure and Kahn topological traversal. */
public final class DagStructure {
    private final Map<String, Set<String>> dependencies;
    private final Map<String, List<String>> dependents;

    public DagStructure(List<String> ids, Map<String, Set<String>> dependencies) {
        if (ids.isEmpty() || ids.size() > 64 || new HashSet<>(ids).size() != ids.size()) {
            throw new IllegalArgumentException("Graph requires 1..64 unique nodes");
        }
        this.dependencies = new LinkedHashMap<>();
        this.dependents = new LinkedHashMap<>();
        ids.forEach(id -> dependents.put(id, new ArrayList<>()));
        for (String id : ids) {
            Set<String> deps = Set.copyOf(dependencies.get(id));
            if (deps.contains(id) || !dependents.keySet().containsAll(deps)) {
                throw new IllegalArgumentException("Unknown or self dependency");
            }
            this.dependencies.put(id, deps);
            deps.forEach(dep -> dependents.get(dep).add(id));
        }
        Map<String, Integer> counts = indegrees();
        Deque<String> ready = new ArrayDeque<>();
        counts.forEach((id, count) -> { if (count == 0) ready.add(id); });
        int visited = 0;
        while (!ready.isEmpty()) {
            String id = ready.remove(); visited++;
            for (String child : dependents.get(id)) {
                if (counts.compute(child, (key, count) -> count - 1) == 0) ready.add(child);
            }
        }
        if (visited != ids.size()) throw new IllegalArgumentException("Graph contains a cycle");
    }

    public Map<String, Integer> indegrees() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        dependencies.forEach((id, deps) -> counts.put(id, deps.size()));
        return counts;
    }

    public List<String> dependents(String id) { return List.copyOf(dependents.get(id)); }
}
