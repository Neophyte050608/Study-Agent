package io.github.imzmq.interview.learning.application.planning;

import io.github.imzmq.interview.agent.application.contract.execution.DagNode;
import io.github.imzmq.interview.agent.application.contract.execution.DagNodeResult;
import io.github.imzmq.interview.agent.application.contract.execution.DagNodeStatus;
import io.github.imzmq.interview.learning.application.contract.result.LearningPlanEvidence;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
final class LearningPlanFixedNodeFactory {
    private final LearningPlanNodeOperations operations;

    LearningPlanFixedNodeFactory(LearningPlanNodeOperations operations) {
        this.operations = operations;
    }

    List<DagNode> nodes(String owner, String subject, String instruction) {
        return List.of(
                new DagNode("profile", Set.of(), true,
                        inputs -> operations.readProfile(owner).text()),
                new DagNode("evidence", Set.of(), true,
                        inputs -> operations.readEvidence(subject, 4).evidence()),
                new DagNode("compose", Set.of("profile", "evidence"), false,
                        inputs -> compose(subject, instruction, inputs)));
    }

    private String compose(
            String subject,
            String instruction,
            Map<String, DagNodeResult> inputs) {
        List<String> degradations = new ArrayList<>();
        Optional<LearningPlanProfileMaterial> profile = profile(inputs.get("profile"), degradations);
        Optional<LearningPlanEvidenceMaterial> evidence = evidence(inputs.get("evidence"), degradations);
        return operations.compose(subject, instruction, profile, evidence, degradations).plan();
    }

    private static Optional<LearningPlanProfileMaterial> profile(
            DagNodeResult result,
            List<String> degradations) {
        if (result == null || result.status() != DagNodeStatus.SUCCEEDED) {
            degradations.add("PROFILE_UNAVAILABLE");
            return Optional.empty();
        }
        if (!(result.output() instanceof String text)) {
            degradations.add("PROFILE_UNAVAILABLE");
            return Optional.empty();
        }
        if (text.isBlank()) {
            degradations.add("PROFILE_EMPTY");
        }
        return Optional.of(new LearningPlanProfileMaterial(text));
    }

    private static Optional<LearningPlanEvidenceMaterial> evidence(
            DagNodeResult result,
            List<String> degradations) {
        if (result == null || result.status() != DagNodeStatus.SUCCEEDED) {
            degradations.add("EVIDENCE_UNAVAILABLE");
            return Optional.empty();
        }
        if (!(result.output() instanceof List<?> raw)
                || raw.stream().anyMatch(value -> !(value instanceof LearningPlanEvidence))) {
            degradations.add("EVIDENCE_UNAVAILABLE");
            return Optional.empty();
        }
        List<LearningPlanEvidence> values = raw.stream()
                .map(LearningPlanEvidence.class::cast)
                .toList();
        if (values.isEmpty()) {
            degradations.add("EVIDENCE_EMPTY");
        }
        return Optional.of(new LearningPlanEvidenceMaterial(values));
    }
}
