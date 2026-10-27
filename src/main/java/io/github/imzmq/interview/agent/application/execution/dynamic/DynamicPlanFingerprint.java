package io.github.imzmq.interview.agent.application.execution.dynamic;

import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskNode;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskPlan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DynamicPlanFingerprint {

    public String digest(ValidatedDynamicTaskPlan plan) {
        Objects.requireNonNull(plan, "Validated plan is required");
        StringBuilder canonical = new StringBuilder();
        appendText(canonical, "schema", plan.schemaVersion());
        appendText(canonical, "scenario", plan.scenarioId());
        plan.nodes().stream()
                .sorted(java.util.Comparator.comparing(ValidatedDynamicTaskNode::id))
                .forEach(node -> appendNode(canonical, node));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static void appendNode(StringBuilder canonical, ValidatedDynamicTaskNode node) {
        canonical.append("node{");
        appendText(canonical, "id", node.id());
        appendText(canonical, "capability", node.capabilityId());
        canonical.append("version=").append(node.capabilityVersion()).append(';');
        canonical.append("dependencies[");
        node.dependsOn().stream().sorted().forEach(dependency -> appendScalar(canonical, dependency));
        canonical.append("]arguments");
        appendMap(canonical, node.arguments());
        canonical.append('}');
    }

    private static void appendMap(StringBuilder canonical, Map<?, ?> values) {
        canonical.append('{');
        values.entrySet().stream()
                .sorted(java.util.Comparator.comparing(entry -> {
                    if (!(entry.getKey() instanceof String key)) {
                        throw compileFailure();
                    }
                    return key;
                }))
                .forEach(entry -> {
                    if (!(entry.getKey() instanceof String key)) {
                        throw compileFailure();
                    }
                    appendScalar(canonical, key);
                    appendValue(canonical, entry.getValue());
                });
        canonical.append('}');
    }

    private static void appendValue(StringBuilder canonical, Object value) {
        if (value instanceof Map<?, ?> nested) {
            appendMap(canonical, nested);
            return;
        }
        if (value instanceof String || value instanceof Integer || value instanceof Boolean) {
            appendScalar(canonical, value);
            return;
        }
        throw compileFailure();
    }

    private static void appendScalar(StringBuilder canonical, Object value) {
        String type;
        if (value instanceof String) {
            type = "s";
        } else if (value instanceof Integer) {
            type = "i";
        } else if (value instanceof Boolean) {
            type = "b";
        } else {
            throw compileFailure();
        }
        String text = value.toString();
        canonical.append(type).append(text.length()).append(':').append(text).append(';');
    }

    private static void appendText(StringBuilder canonical, String label, String value) {
        canonical.append(label).append('=');
        appendScalar(canonical, value);
    }

    private static IllegalArgumentException compileFailure() {
        return new IllegalArgumentException("PLAN_COMPILE_FAILED");
    }
}
