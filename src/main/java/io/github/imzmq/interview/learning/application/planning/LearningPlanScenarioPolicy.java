package io.github.imzmq.interview.learning.application.planning;

import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanIssue;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanScenarioPolicy;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskNode;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskPlan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class LearningPlanScenarioPolicy implements DynamicPlanScenarioPolicy {
    private static final String PROFILE = "learning.profile.lookup";
    private static final String EVIDENCE = "knowledge.evidence.retrieve";
    private static final String COMPOSE = "learning.plan.compose";
    private static final String POLICY_VIOLATION = "SCENARIO_POLICY_VIOLATION";
    private static final Set<String> ALLOWED_CAPABILITIES = Set.of(PROFILE, EVIDENCE, COMPOSE);

    @Override
    public String scenarioId() {
        return "learning-plan-preview";
    }

    @Override
    public Set<String> allowedCapabilities() {
        return ALLOWED_CAPABILITIES;
    }

    @Override
    public List<DynamicPlanIssue> validate(ValidatedDynamicTaskPlan plan) {
        if (plan == null) {
            return List.of(issue(""));
        }

        List<DynamicPlanIssue> issues = new ArrayList<>();
        List<ValidatedDynamicTaskNode> nodes = plan.nodes();
        if (nodes.size() < 2 || nodes.size() > 3) {
            issues.add(issue(""));
        }

        Map<String, ValidatedDynamicTaskNode> nodesById = new LinkedHashMap<>();
        Map<String, List<ValidatedDynamicTaskNode>> byCapability = new HashMap<>();
        for (ValidatedDynamicTaskNode node : nodes) {
            nodesById.put(node.id(), node);
            byCapability.computeIfAbsent(node.capabilityId(), ignored -> new ArrayList<>()).add(node);
            if (!ALLOWED_CAPABILITIES.contains(node.capabilityId())) {
                issues.add(issue(node.id()));
            }
        }

        List<ValidatedDynamicTaskNode> profiles = byCapability.getOrDefault(PROFILE, List.of());
        List<ValidatedDynamicTaskNode> evidenceNodes = byCapability.getOrDefault(EVIDENCE, List.of());
        List<ValidatedDynamicTaskNode> composeNodes = byCapability.getOrDefault(COMPOSE, List.of());
        if (profiles.size() > 1) {
            issues.add(issue(profiles.get(1).id()));
        }
        if (evidenceNodes.size() > 1) {
            issues.add(issue(evidenceNodes.get(1).id()));
        }
        if (composeNodes.size() != 1) {
            issues.add(issue(composeNodes.isEmpty() ? "" : composeNodes.get(1).id()));
        }

        Set<String> sourceIds = new LinkedHashSet<>();
        profiles.forEach(node -> sourceIds.add(node.id()));
        evidenceNodes.forEach(node -> sourceIds.add(node.id()));
        if (sourceIds.isEmpty()) {
            issues.add(issue(""));
        }

        for (ValidatedDynamicTaskNode profile : profiles) {
            if (profile.dependsOn().stream().anyMatch(sourceIds::contains)) {
                issues.add(issue(profile.id()));
            }
        }
        for (ValidatedDynamicTaskNode evidence : evidenceNodes) {
            if (evidence.dependsOn().stream().anyMatch(sourceIds::contains)) {
                issues.add(issue(evidence.id()));
            }
        }

        if (composeNodes.size() == 1) {
            ValidatedDynamicTaskNode compose = composeNodes.getFirst();
            if (!new LinkedHashSet<>(compose.dependsOn()).equals(sourceIds)
                    || compose.dependsOn().size() != sourceIds.size()) {
                issues.add(issue(compose.id()));
            }

            Map<String, Integer> dependentCounts = new HashMap<>();
            for (ValidatedDynamicTaskNode node : nodes) {
                for (String dependency : node.dependsOn()) {
                    if (nodesById.containsKey(dependency)) {
                        dependentCounts.merge(dependency, 1, Integer::sum);
                    }
                }
            }
            List<String> terminalIds = nodes.stream()
                    .map(ValidatedDynamicTaskNode::id)
                    .filter(id -> dependentCounts.getOrDefault(id, 0) == 0)
                    .toList();
            if (terminalIds.size() != 1 || !terminalIds.getFirst().equals(compose.id())) {
                issues.add(issue(compose.id()));
            }
        }
        return List.copyOf(issues);
    }

    private static DynamicPlanIssue issue(String nodeId) {
        return new DynamicPlanIssue(POLICY_VIOLATION, nodeId);
    }
}
