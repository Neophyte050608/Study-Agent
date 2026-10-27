package io.github.imzmq.interview.learning.application.planning;

import io.github.imzmq.interview.agent.application.contract.execution.DagNodeResult;
import io.github.imzmq.interview.agent.application.contract.execution.DagNodeStatus;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicCapabilityDescriptor;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicEffectType;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicExecutionContext;
import io.github.imzmq.interview.agent.application.contract.extension.DynamicNodeCapability;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public final class LearningPlanComposeCapability
        implements DynamicNodeCapability<LearningPlanComposeCapability.NoArguments> {
    private static final DynamicCapabilityDescriptor DESCRIPTOR = new DynamicCapabilityDescriptor(
            "learning.plan.compose", 1, DynamicEffectType.READ_ONLY, Map.of(),
            Set.of("learning.profile.lookup", "knowledge.evidence.retrieve"), 3, false);

    private final LearningPlanNodeOperations operations;

    public LearningPlanComposeCapability(LearningPlanNodeOperations operations) {
        this.operations = operations;
    }

    @Override
    public DynamicCapabilityDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public NoArguments decodeArguments(Map<String, Object> normalizedArguments) {
        if (!normalizedArguments.isEmpty()) {
            throw new IllegalArgumentException("INVALID_ARGUMENTS");
        }
        return new NoArguments();
    }

    @Override
    public Object execute(
            DynamicExecutionContext context,
            Map<String, DagNodeResult> upstream,
            NoArguments arguments) {
        List<String> degradations = new ArrayList<>();
        Optional<LearningPlanProfileMaterial> profile = selectedProfile(upstream, degradations);
        Optional<LearningPlanEvidenceMaterial> evidence = selectedEvidence(upstream, degradations);
        return operations.compose(
                context.topic(), context.request(), profile, evidence, degradations);
    }

    private static Optional<LearningPlanProfileMaterial> selectedProfile(
            Map<String, DagNodeResult> upstream,
            List<String> degradations) {
        if (!upstream.containsKey("profile")) {
            return Optional.empty();
        }
        DagNodeResult result = upstream.get("profile");
        if (result == null || result.status() != DagNodeStatus.SUCCEEDED
                || !(result.output() instanceof LearningPlanProfileMaterial material)) {
            degradations.add("PROFILE_UNAVAILABLE");
            return Optional.empty();
        }
        if (material.text().isBlank()) {
            degradations.add("PROFILE_EMPTY");
        }
        return Optional.of(material);
    }

    private static Optional<LearningPlanEvidenceMaterial> selectedEvidence(
            Map<String, DagNodeResult> upstream,
            List<String> degradations) {
        if (!upstream.containsKey("evidence")) {
            return Optional.empty();
        }
        DagNodeResult result = upstream.get("evidence");
        if (result == null || result.status() != DagNodeStatus.SUCCEEDED
                || !(result.output() instanceof LearningPlanEvidenceMaterial material)) {
            degradations.add("EVIDENCE_UNAVAILABLE");
            return Optional.empty();
        }
        if (material.evidence().isEmpty()) {
            degradations.add("EVIDENCE_EMPTY");
        }
        return Optional.of(material);
    }

    record NoArguments() {
    }
}
