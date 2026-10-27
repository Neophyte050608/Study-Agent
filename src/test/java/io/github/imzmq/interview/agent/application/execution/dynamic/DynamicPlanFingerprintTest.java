package io.github.imzmq.interview.agent.application.execution.dynamic;

import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskNode;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskPlan;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicPlanFingerprintTest {
    private final DynamicPlanFingerprint fingerprint = new DynamicPlanFingerprint();

    @Test
    void fingerprintIgnoresCandidateMapInsertionOrderButIncludesCapabilityVersion() {
        String first = fingerprint.digest(planWithArguments(linkedMap("limit", 4, "enabled", true), 1));
        String reordered = fingerprint.digest(planWithArguments(linkedMap("enabled", true, "limit", 4), 1));
        String upgraded = fingerprint.digest(planWithArguments(linkedMap("limit", 4, "enabled", true), 2));

        assertThat(first).isEqualTo(reordered).matches("[0-9a-f]{64}");
        assertThat(upgraded).isNotEqualTo(first);
    }

    @Test
    void canonicalizesNodeDependencyAndNestedMapOrdering() {
        ValidatedDynamicTaskPlan first = new ValidatedDynamicTaskPlan("1", "test", List.of(
                node("compose", "result.compose", 1, List.of("right", "left"), Map.of()),
                node("right", "source.read", 1, List.of(), linkedMap("nested", linkedMap("b", 2, "a", 1))),
                node("left", "source.read", 1, List.of(), Map.of())), 3);
        ValidatedDynamicTaskPlan reordered = new ValidatedDynamicTaskPlan("1", "test", List.of(
                node("left", "source.read", 1, List.of(), Map.of()),
                node("right", "source.read", 1, List.of(), linkedMap("nested", linkedMap("a", 1, "b", 2))),
                node("compose", "result.compose", 1, List.of("left", "right"), Map.of())), 3);

        assertThat(fingerprint.digest(first)).isEqualTo(fingerprint.digest(reordered));
    }

    @Test
    void includesOnlyPlanStructureAndRejectsUnsupportedArgumentValues() {
        ValidatedDynamicTaskPlan plan = planWithArguments(Map.of(
                "owner", "owner-secret",
                "topic", "topic-secret",
                "request", "request-secret"), 1);
        String digest = fingerprint.digest(plan);

        assertThat(digest).matches("[0-9a-f]{64}")
                .doesNotContain("owner-secret", "topic-secret", "request-secret");
        assertThatThrownBy(() -> fingerprint.digest(planWithArguments(Map.of("list", List.of(1)), 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("PLAN_COMPILE_FAILED");
    }

    private static ValidatedDynamicTaskPlan planWithArguments(Map<String, Object> arguments, int version) {
        return new ValidatedDynamicTaskPlan("1", "test", List.of(
                node("source", "source.read", version, List.of(), arguments)), 1);
    }

    private static ValidatedDynamicTaskNode node(
            String id,
            String capability,
            int version,
            List<String> dependencies,
            Map<String, Object> arguments) {
        return new ValidatedDynamicTaskNode(id, capability, version, dependencies, arguments);
    }

    private static Map<String, Object> linkedMap(Object... entries) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            values.put((String) entries[index], entries[index + 1]);
        }
        return values;
    }
}
