package io.github.imzmq.interview.agent.application.execution.dynamic;

import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanObservationOperations;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskNode;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskPlan;
import io.github.imzmq.interview.observability.application.contract.OperationalObservationPublisher;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public final class DynamicPlanObservationPublisher implements DynamicPlanObservationOperations {
    private static final String CATEGORY = "agent-planning";
    private static final String NAME = "learning-plan-preview";
    private static final int MAX_TEXT_LENGTH = 120;
    private static final Set<String> ERROR_CODES = Set.of(
            "UNSUPPORTED_SCHEMA_VERSION", "PLAN_EMPTY", "PLAN_TOO_LARGE",
            "INVALID_NODE_ID", "DUPLICATE_NODE_ID", "UNKNOWN_CAPABILITY",
            "INVALID_ARGUMENTS", "UNKNOWN_DEPENDENCY", "CYCLIC_DEPENDENCY",
            "MAX_DEPTH_EXCEEDED", "MAX_DEPENDENCIES_EXCEEDED", "MAX_FAN_OUT_EXCEEDED",
            "DISALLOWED_EFFECT", "INCOMPATIBLE_DEPENDENCY", "MISSING_FINAL_OUTPUT",
            "SCENARIO_POLICY_VIOLATION", "PLAN_BUDGET_EXCEEDED", "PLAN_COMPILE_FAILED",
            "INVALID_PLAN_JSON", "PLANNER_TIMEOUT", "PLANNER_UNAVAILABLE",
            "SHADOW_TIMEOUT", "SHADOW_OVERLOADED", "PLANNING_FAILED");

    private final OperationalObservationPublisher delegate;

    public DynamicPlanObservationPublisher(OperationalObservationPublisher delegate) {
        this.delegate = delegate;
    }

    @Override
    public void requested(String plannerType, String plannerVersion) {
        Map<String, Object> attributes = plannerAttributes(plannerType, plannerVersion);
        publish("requested", "REQUESTED", attributes);
    }

    @Override
    public void generated(String plannerType, String plannerVersion,
                          ValidatedDynamicTaskPlan plan, String digest, long durationMs) {
        publish("generated", "GENERATED",
                planAttributes(plannerType, plannerVersion, plan, digest, durationMs));
    }

    @Override
    public void rejected(String plannerType, String plannerVersion,
                         String errorCode, long durationMs) {
        Map<String, Object> attributes = plannerAttributes(plannerType, plannerVersion);
        attributes.put("errorCode", stableErrorCode(errorCode));
        attributes.put("durationMs", nonNegative(durationMs));
        publish("rejected", "REJECTED", attributes);
    }

    @Override
    public void compiled(String plannerType, String plannerVersion,
                         ValidatedDynamicTaskPlan plan, String digest, long durationMs) {
        publish("compiled", "COMPILED",
                planAttributes(plannerType, plannerVersion, plan, digest, durationMs));
    }

    @Override
    public void fallback(String plannerType, String plannerVersion, String errorCode) {
        Map<String, Object> attributes = plannerAttributes(plannerType, plannerVersion);
        attributes.put("errorCode", stableErrorCode(errorCode));
        attributes.put("fallbackTarget", "fixed");
        publish("fallback", "FALLBACK", attributes);
    }

    @Override
    public void shadowCompared(String plannerType, String plannerVersion,
                               String ruleDigest, String shadowDigest,
                               boolean same, int nodeDelta, long durationMs) {
        Map<String, Object> attributes = plannerAttributes(plannerType, plannerVersion);
        attributes.put("ruleDigest", bounded(ruleDigest));
        attributes.put("shadowDigest", bounded(shadowDigest));
        attributes.put("same", same);
        attributes.put("nodeDelta", nodeDelta);
        attributes.put("durationMs", nonNegative(durationMs));
        publish("shadow_compared", "COMPARED", attributes);
    }

    private void publish(String event, String status, Map<String, Object> attributes) {
        try {
            delegate.publish("dynamic_plan." + event, CATEGORY, NAME, status, attributes);
        } catch (RuntimeException ignored) {
            // Operational telemetry must never change planning or fallback behavior.
        }
    }

    private static Map<String, Object> planAttributes(
            String plannerType, String plannerVersion, ValidatedDynamicTaskPlan plan,
            String digest, long durationMs) {
        Map<String, Object> attributes = plannerAttributes(plannerType, plannerVersion);
        attributes.put("scenario", bounded(plan.scenarioId()));
        attributes.put("schemaVersion", bounded(plan.schemaVersion()));
        attributes.put("nodeCount", plan.nodes().size());
        attributes.put("depth", depth(plan));
        attributes.put("capabilities", capabilities(plan));
        attributes.put("planDigest", bounded(digest));
        attributes.put("durationMs", nonNegative(durationMs));
        return attributes;
    }

    private static Map<String, Object> plannerAttributes(String plannerType, String plannerVersion) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("plannerType", bounded(plannerType));
        attributes.put("plannerVersion", bounded(plannerVersion));
        return attributes;
    }

    private static String capabilities(ValidatedDynamicTaskPlan plan) {
        String joined = plan.nodes().stream()
                .map(ValidatedDynamicTaskNode::capabilityId)
                .distinct()
                .sorted()
                .collect(Collectors.joining(","));
        return bounded(joined);
    }

    private static int depth(ValidatedDynamicTaskPlan plan) {
        Map<String, ValidatedDynamicTaskNode> nodes = plan.nodes().stream()
                .collect(Collectors.toMap(ValidatedDynamicTaskNode::id, node -> node));
        Map<String, Integer> memo = new HashMap<>();
        return plan.nodes().stream().mapToInt(node -> depth(node, nodes, memo)).max().orElse(0);
    }

    private static int depth(ValidatedDynamicTaskNode node,
                             Map<String, ValidatedDynamicTaskNode> nodes,
                             Map<String, Integer> memo) {
        Integer known = memo.get(node.id());
        if (known != null) {
            return known;
        }
        int value = 1 + node.dependsOn().stream()
                .map(nodes::get)
                .filter(java.util.Objects::nonNull)
                .mapToInt(dependency -> depth(dependency, nodes, memo))
                .max().orElse(0);
        memo.put(node.id(), value);
        return value;
    }

    private static String stableErrorCode(String errorCode) {
        return ERROR_CODES.contains(errorCode) ? errorCode : "PLANNING_FAILED";
    }

    private static long nonNegative(long value) {
        return Math.max(0L, value);
    }

    private static String bounded(String value) {
        String safe = value == null ? "" : value.trim();
        return safe.length() <= MAX_TEXT_LENGTH ? safe : safe.substring(0, MAX_TEXT_LENGTH);
    }
}
