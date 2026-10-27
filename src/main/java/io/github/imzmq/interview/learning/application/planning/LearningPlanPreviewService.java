package io.github.imzmq.interview.learning.application.planning;

import io.github.imzmq.interview.agent.application.contract.execution.DagExecutionOperations;
import io.github.imzmq.interview.agent.application.contract.execution.DagExecutionResult;
import io.github.imzmq.interview.agent.application.contract.execution.DagNode;
import io.github.imzmq.interview.agent.application.contract.execution.DagNodeResult;
import io.github.imzmq.interview.agent.application.contract.execution.DagNodeStatus;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicExecutionContext;
import io.github.imzmq.interview.learning.application.contract.result.LearningPlanEvidence;
import io.github.imzmq.interview.learning.application.contract.result.LearningPlanPreview;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public final class LearningPlanPreviewService {
    private final DagExecutionOperations executor;
    private final LearningPlanDynamicPlanningService dynamicPlanning;

    @Autowired
    public LearningPlanPreviewService(
            DagExecutionOperations executor,
            LearningPlanDynamicPlanningService dynamicPlanning) {
        this.executor = executor;
        this.dynamicPlanning = dynamicPlanning;
    }

    public LearningPlanPreview preview(String userId, String topic, String request) {
        String owner = required(userId, 200, "Execution user");
        String subject = required(topic, 200, "Topic");
        String instruction = request == null ? "" : request.trim();
        if (instruction.length() > 2000) {
            throw new IllegalArgumentException("Request exceeds 2000 characters");
        }
        DynamicExecutionContext context = new DynamicExecutionContext(owner, subject, instruction);
        return present(executor.execute(
                dynamicPlanning.nodes(context), Duration.ofSeconds(75)));
    }

    LearningPlanPreview present(DagExecutionResult result) {
        Materials materials = materials(result.nodes());
        DagNodeResult compose = result.nodes().get("compose");
        String plan = composeText(compose);
        Map<String, LearningPlanPreview.NodeSummary> states = new LinkedHashMap<>();
        result.nodes().forEach((id, node) -> states.put(
                id, new LearningPlanPreview.NodeSummary(node.status(), node.code())));
        boolean composed = !plan.isBlank()
                && ("COMPLETED".equals(result.code()) || "FAILED".equals(result.code()));
        String status = composed
                ? (materials.degradations().isEmpty() ? "COMPLETED" : "DEGRADED")
                : ("FAILED".equals(result.code()) ? "FAILED" : result.code());
        return new LearningPlanPreview(
                status,
                composed ? plan : "",
                !materials.profile().isBlank(),
                !materials.evidence().isEmpty(),
                materials.evidence(),
                materials.degradations(),
                states);
    }

    private Materials materials(Map<String, DagNodeResult> nodes) {
        List<String> reasons = new ArrayList<>();
        String profileText = profileText(nodes.get("profile"), reasons);
        List<LearningPlanEvidence> evidence = evidence(nodes.get("evidence"), reasons);
        return new Materials(profileText, evidence, List.copyOf(reasons));
    }

    private static String profileText(DagNodeResult result, List<String> reasons) {
        if (result == null) {
            return "";
        }
        if (result.status() != DagNodeStatus.SUCCEEDED) {
            reasons.add("PROFILE_UNAVAILABLE");
            return "";
        }
        String value;
        if (result.output() instanceof String text) {
            value = text;
        } else if (result.output() instanceof LearningPlanProfileMaterial material) {
            value = material.text();
        } else {
            reasons.add("PROFILE_UNAVAILABLE");
            return "";
        }
        if (value.isBlank()) {
            reasons.add("PROFILE_EMPTY");
        }
        return value;
    }

    private static List<LearningPlanEvidence> evidence(
            DagNodeResult result,
            List<String> reasons) {
        if (result == null) {
            return List.of();
        }
        if (result.status() != DagNodeStatus.SUCCEEDED) {
            reasons.add("EVIDENCE_UNAVAILABLE");
            return List.of();
        }
        List<LearningPlanEvidence> values;
        if (result.output() instanceof LearningPlanEvidenceMaterial material) {
            values = material.evidence();
        } else if (result.output() instanceof List<?> raw
                && raw.stream().allMatch(value -> value instanceof LearningPlanEvidence)) {
            values = raw.stream().map(LearningPlanEvidence.class::cast).toList();
        } else {
            reasons.add("EVIDENCE_UNAVAILABLE");
            return List.of();
        }
        if (values.isEmpty()) {
            reasons.add("EVIDENCE_EMPTY");
        }
        return List.copyOf(values);
    }

    private static String composeText(DagNodeResult result) {
        if (result == null || result.status() != DagNodeStatus.SUCCEEDED) {
            return "";
        }
        if (result.output() instanceof String text) {
            return text;
        }
        if (result.output() instanceof LearningPlanComposeMaterial material) {
            return material.plan();
        }
        return "";
    }

    private static String required(String value, int maximum, String name) {
        if (value == null || value.isBlank() || value.trim().length() > maximum) {
            throw new IllegalArgumentException(
                    name + " is required and must be at most " + maximum + " characters");
        }
        return value.trim();
    }

    private record Materials(
            String profile,
            List<LearningPlanEvidence> evidence,
            List<String> degradations) {
    }
}
