package io.github.imzmq.interview.agent.domain.execution;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class DurableRetrySelectionTest {
    private final Map<String, Set<String>> deps = Map.of("profile", Set.of(), "evidence", Set.of(),
            "compose", Set.of("profile", "evidence"));
    @Test void resetsFailedRootAndSuccessfulDescendantButPreservesSuccessfulSibling() {
        assertEquals(Set.of("evidence", "compose"), DurableRetrySelection.select(deps,
                Map.of("profile", "SUCCEEDED", "evidence", "FAILED", "compose", "SUCCEEDED"),
                Map.of("profile", 1, "evidence", 1, "compose", 1), Set.of("evidence"), 3));
    }
    @Test void rejectsSuccessfulRootAndAnyExhaustedNodeInClosure() {
        var states = Map.of("profile", "SUCCEEDED", "evidence", "FAILED", "compose", "SUCCEEDED");
        assertThrows(IllegalArgumentException.class, () -> DurableRetrySelection.select(deps, states,
                Map.of("profile", 1, "evidence", 1, "compose", 1), Set.of("profile"), 3));
        assertThrows(IllegalArgumentException.class, () -> DurableRetrySelection.select(deps, states,
                Map.of("profile", 1, "evidence", 1, "compose", 3), Set.of("evidence"), 3));
    }
    @Test void emptySelectionUsesFailuresAndRejectsEmptyDataOnlyDegradation() {
        assertEquals(Set.of("compose"), DurableRetrySelection.select(deps,
                Map.of("profile", "SUCCEEDED", "evidence", "SUCCEEDED", "compose", "FAILED"),
                Map.of("profile", 1, "evidence", 1, "compose", 1), Set.of(), 3));
        assertThrows(IllegalArgumentException.class, () -> DurableRetrySelection.select(deps,
                Map.of("profile", "SUCCEEDED", "evidence", "SUCCEEDED", "compose", "SUCCEEDED"),
                Map.of("profile", 1, "evidence", 1, "compose", 1), Set.of(), 3));
    }
}
