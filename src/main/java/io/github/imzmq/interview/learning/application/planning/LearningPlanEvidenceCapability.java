package io.github.imzmq.interview.learning.application.planning;

import io.github.imzmq.interview.agent.application.contract.execution.DagNodeResult;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicArgumentDefinition;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicArgumentType;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicCapabilityDescriptor;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicEffectType;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicExecutionContext;
import io.github.imzmq.interview.agent.application.contract.extension.DynamicNodeCapability;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public final class LearningPlanEvidenceCapability
        implements DynamicNodeCapability<LearningPlanEvidenceCapability.EvidenceArguments> {
    private static final DynamicCapabilityDescriptor DESCRIPTOR = new DynamicCapabilityDescriptor(
            "knowledge.evidence.retrieve", 1, DynamicEffectType.READ_ONLY,
            Map.of("limit", new DynamicArgumentDefinition(
                    "limit", DynamicArgumentType.INTEGER, false, 4, 1L, 5L)),
            Set.of(), 2, true);

    private final LearningPlanNodeOperations operations;

    public LearningPlanEvidenceCapability(LearningPlanNodeOperations operations) {
        this.operations = operations;
    }

    @Override
    public DynamicCapabilityDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public EvidenceArguments decodeArguments(Map<String, Object> normalizedArguments) {
        Object value = normalizedArguments.get("limit");
        if (!(value instanceof Integer limit) || normalizedArguments.size() != 1) {
            throw new IllegalArgumentException("INVALID_ARGUMENTS");
        }
        return new EvidenceArguments(limit);
    }

    @Override
    public Object execute(
            DynamicExecutionContext context,
            Map<String, DagNodeResult> upstream,
            EvidenceArguments arguments) {
        return operations.readEvidence(context.topic(), arguments.limit());
    }

    record EvidenceArguments(int limit) {
    }
}
