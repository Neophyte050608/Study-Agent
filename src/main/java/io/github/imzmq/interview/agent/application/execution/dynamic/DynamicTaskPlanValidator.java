package io.github.imzmq.interview.agent.application.execution.dynamic;

import io.github.imzmq.interview.agent.application.contract.execution.dynamic.CandidateDynamicTaskNode;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.CandidateDynamicTaskPlan;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicArgumentDefinition;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicArgumentType;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicCapabilityDescriptor;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicEffectType;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanIssue;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanScenarioPolicy;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanValidationResult;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanningLimits;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskNode;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskPlan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class DynamicTaskPlanValidator {
    private static final String SUPPORTED_SCHEMA_VERSION = "1";
    private static final Pattern NODE_ID = Pattern.compile("^[a-z][a-z0-9-]{0,31}$");

    private final Map<String, DynamicCapabilityDescriptor> descriptors;

    public DynamicTaskPlanValidator(Collection<DynamicCapabilityDescriptor> descriptors) {
        Objects.requireNonNull(descriptors, "Capability descriptors are required");
        LinkedHashMap<String, DynamicCapabilityDescriptor> byId = new LinkedHashMap<>();
        for (DynamicCapabilityDescriptor descriptor : descriptors) {
            Objects.requireNonNull(descriptor, "Capability descriptor is required");
            if (byId.putIfAbsent(descriptor.capabilityId(), descriptor) != null) {
                throw new IllegalArgumentException("Capability IDs must be unique");
            }
        }
        this.descriptors = Map.copyOf(byId);
    }

    public DynamicPlanValidationResult validate(
            CandidateDynamicTaskPlan candidate,
            DynamicPlanScenarioPolicy policy,
            DynamicPlanningLimits limits) {
        Objects.requireNonNull(policy, "Scenario policy is required");
        Objects.requireNonNull(limits, "Planning limits are required");

        List<DynamicPlanIssue> issues = new ArrayList<>();
        if (candidate == null) {
            add(issues, "PLAN_EMPTY", null);
            return invalid(issues, limits);
        }
        if (!SUPPORTED_SCHEMA_VERSION.equals(candidate.schemaVersion())) {
            add(issues, "UNSUPPORTED_SCHEMA_VERSION", null);
        }
        List<CandidateDynamicTaskNode> nodes = candidate.nodes();
        if (nodes.isEmpty()) {
            add(issues, "PLAN_EMPTY", null);
            return invalid(issues, limits);
        }
        if (nodes.size() > limits.maxNodes()) {
            add(issues, "PLAN_TOO_LARGE", null);
        }

        Map<String, CandidateDynamicTaskNode> nodesById = new LinkedHashMap<>();
        Set<String> allowedCapabilities = safeAllowedCapabilities(policy);
        for (CandidateDynamicTaskNode node : nodes) {
            if (node == null) {
                add(issues, "INVALID_NODE_ID", null);
                continue;
            }
            if (node.id() == null || !NODE_ID.matcher(node.id()).matches()) {
                add(issues, "INVALID_NODE_ID", node.id());
            }
            if (node.id() != null && nodesById.putIfAbsent(node.id(), node) != null) {
                add(issues, "DUPLICATE_NODE_ID", node.id());
            }
            DynamicCapabilityDescriptor descriptor = descriptors.get(node.capability());
            if (descriptor == null) {
                add(issues, "UNKNOWN_CAPABILITY", node.id());
                continue;
            }
            if (!allowedCapabilities.contains(descriptor.capabilityId())) {
                add(issues, "SCENARIO_POLICY_VIOLATION", node.id());
            }
            if (descriptor.effectType() != DynamicEffectType.READ_ONLY) {
                add(issues, "DISALLOWED_EFFECT", node.id());
            }
        }

        Map<String, Integer> fanOut = new HashMap<>();
        Map<String, List<String>> uniqueDependencies = new LinkedHashMap<>();
        for (CandidateDynamicTaskNode node : nodes) {
            if (node == null || node.id() == null || nodesById.get(node.id()) != node) {
                continue;
            }
            LinkedHashSet<String> dependencies = new LinkedHashSet<>();
            for (String dependencyId : node.dependsOn()) {
                if (!dependencies.add(dependencyId)) {
                    add(issues, "INVALID_ARGUMENTS", node.id());
                    continue;
                }
                CandidateDynamicTaskNode dependency = nodesById.get(dependencyId);
                if (dependency == null) {
                    add(issues, "UNKNOWN_DEPENDENCY", node.id());
                    continue;
                }
                fanOut.merge(dependencyId, 1, Integer::sum);
                DynamicCapabilityDescriptor nodeDescriptor = descriptors.get(node.capability());
                DynamicCapabilityDescriptor dependencyDescriptor = descriptors.get(dependency.capability());
                if (nodeDescriptor != null && dependencyDescriptor != null
                        && !nodeDescriptor.allowedDependencyCapabilities()
                        .contains(dependencyDescriptor.capabilityId())) {
                    add(issues, "INCOMPATIBLE_DEPENDENCY", node.id());
                }
            }
            uniqueDependencies.put(node.id(), List.copyOf(dependencies));
            if (dependencies.size() > limits.maxDependenciesPerNode()) {
                add(issues, "MAX_DEPENDENCIES_EXCEEDED", node.id());
            }
        }
        fanOut.forEach((nodeId, count) -> {
            if (count > limits.maxFanOut()) {
                add(issues, "MAX_FAN_OUT_EXCEEDED", nodeId);
            }
        });

        Traversal traversal = traverse(nodesById, uniqueDependencies);
        if (traversal.visitedCount() != nodesById.size()) {
            add(issues, "CYCLIC_DEPENDENCY", null);
        }
        traversal.depths().forEach((nodeId, depth) -> {
            if (depth > limits.maxDepth()) {
                add(issues, "MAX_DEPTH_EXCEEDED", nodeId);
            }
        });

        validateFinalOutput(nodesById, uniqueDependencies, fanOut, issues);

        List<ValidatedDynamicTaskNode> normalizedNodes = new ArrayList<>();
        int estimatedCost = 0;
        for (CandidateDynamicTaskNode node : nodes) {
            if (node == null || node.id() == null || nodesById.get(node.id()) != node) {
                continue;
            }
            DynamicCapabilityDescriptor descriptor = descriptors.get(node.capability());
            if (descriptor == null) {
                continue;
            }
            Map<String, Object> normalizedArguments = normalizeArguments(node, descriptor, issues);
            normalizedNodes.add(new ValidatedDynamicTaskNode(
                    node.id(),
                    descriptor.capabilityId(),
                    descriptor.capabilityVersion(),
                    node.dependsOn(),
                    normalizedArguments));
            estimatedCost += descriptor.estimatedCost();
        }
        if (estimatedCost > limits.maxEstimatedCost()) {
            add(issues, "PLAN_BUDGET_EXCEEDED", null);
        }

        if (!issues.isEmpty()) {
            return invalid(issues, limits);
        }

        ValidatedDynamicTaskPlan plan = new ValidatedDynamicTaskPlan(
                candidate.schemaVersion(), policy.scenarioId(), normalizedNodes, estimatedCost);
        List<DynamicPlanIssue> policyIssues = policy.validate(plan);
        if (policyIssues != null) {
            issues.addAll(policyIssues.stream().filter(Objects::nonNull).toList());
        }
        if (!issues.isEmpty()) {
            return invalid(issues, limits);
        }
        return DynamicPlanValidationResult.valid(plan);
    }

    private static Set<String> safeAllowedCapabilities(DynamicPlanScenarioPolicy policy) {
        Set<String> allowed = policy.allowedCapabilities();
        return allowed == null ? Set.of() : Set.copyOf(allowed);
    }

    private static Traversal traverse(
            Map<String, CandidateDynamicTaskNode> nodesById,
            Map<String, List<String>> dependencies) {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new LinkedHashMap<>();
        nodesById.keySet().forEach(id -> {
            indegree.put(id, 0);
            dependents.put(id, new ArrayList<>());
        });
        dependencies.forEach((nodeId, nodeDependencies) -> {
            for (String dependencyId : nodeDependencies) {
                if (nodesById.containsKey(dependencyId)) {
                    indegree.merge(nodeId, 1, Integer::sum);
                    dependents.get(dependencyId).add(nodeId);
                }
            }
        });

        ArrayDeque<String> ready = new ArrayDeque<>();
        Map<String, Integer> depths = new LinkedHashMap<>();
        indegree.forEach((id, count) -> {
            if (count == 0) {
                ready.addLast(id);
                depths.put(id, 1);
            }
        });
        int visited = 0;
        while (!ready.isEmpty()) {
            String current = ready.removeFirst();
            visited++;
            int nextDepth = depths.getOrDefault(current, 1) + 1;
            for (String dependent : dependents.get(current)) {
                depths.merge(dependent, nextDepth, Math::max);
                int remaining = indegree.merge(dependent, -1, Integer::sum);
                if (remaining == 0) {
                    ready.addLast(dependent);
                }
            }
        }
        return new Traversal(visited, Map.copyOf(depths));
    }

    private static void validateFinalOutput(
            Map<String, CandidateDynamicTaskNode> nodesById,
            Map<String, List<String>> dependencies,
            Map<String, Integer> fanOut,
            List<DynamicPlanIssue> issues) {
        List<String> terminals = nodesById.keySet().stream()
                .filter(id -> fanOut.getOrDefault(id, 0) == 0)
                .toList();
        if (terminals.size() != 1) {
            add(issues, "MISSING_FINAL_OUTPUT", null);
            return;
        }

        Set<String> reachesTerminal = new LinkedHashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(terminals.getFirst());
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!reachesTerminal.add(current)) {
                continue;
            }
            for (String dependency : dependencies.getOrDefault(current, List.of())) {
                if (nodesById.containsKey(dependency)) {
                    pending.addLast(dependency);
                }
            }
        }
        if (reachesTerminal.size() != nodesById.size()) {
            add(issues, "MISSING_FINAL_OUTPUT", null);
        }
    }

    private static Map<String, Object> normalizeArguments(
            CandidateDynamicTaskNode node,
            DynamicCapabilityDescriptor descriptor,
            List<DynamicPlanIssue> issues) {
        Map<String, Object> supplied = node.arguments();
        boolean invalid = supplied.keySet().stream()
                .anyMatch(name -> !descriptor.arguments().containsKey(name));
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        for (DynamicArgumentDefinition definition : descriptor.arguments().values()) {
            Object value = supplied.get(definition.name());
            if (!supplied.containsKey(definition.name())) {
                if (definition.required() && definition.defaultValue() == null) {
                    invalid = true;
                    continue;
                }
                value = definition.defaultValue();
            }
            if (value != null) {
                if (!validArgument(value, definition)) {
                    invalid = true;
                } else {
                    normalized.put(definition.name(), value);
                }
            }
        }
        if (invalid) {
            add(issues, "INVALID_ARGUMENTS", node.id());
        }
        return Map.copyOf(normalized);
    }

    private static boolean validArgument(Object value, DynamicArgumentDefinition definition) {
        if (definition.type() == DynamicArgumentType.STRING) {
            if (!(value instanceof String stringValue)) {
                return false;
            }
            return withinBounds(stringValue.codePointCount(0, stringValue.length()), definition);
        }
        if (definition.type() == DynamicArgumentType.INTEGER) {
            return value instanceof Integer integerValue && withinBounds(integerValue.longValue(), definition);
        }
        return definition.type() == DynamicArgumentType.BOOLEAN && value instanceof Boolean;
    }

    private static boolean withinBounds(long value, DynamicArgumentDefinition definition) {
        return (definition.minimum() == null || value >= definition.minimum())
                && (definition.maximum() == null || value <= definition.maximum());
    }

    private static void add(List<DynamicPlanIssue> issues, String code, String nodeId) {
        issues.add(new DynamicPlanIssue(code, nodeId));
    }

    private static DynamicPlanValidationResult invalid(
            List<DynamicPlanIssue> issues,
            DynamicPlanningLimits limits) {
        return DynamicPlanValidationResult.invalid(
                issues.stream().limit(limits.maxIssues()).toList());
    }

    private record Traversal(int visitedCount, Map<String, Integer> depths) {
    }
}
