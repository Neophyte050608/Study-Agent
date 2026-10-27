package io.github.imzmq.interview.learning.application.planning;

import io.github.imzmq.interview.agent.application.contract.execution.DagNodeResult;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicCapabilityDescriptor;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicEffectType;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicExecutionContext;
import io.github.imzmq.interview.agent.application.contract.extension.DynamicNodeCapability;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public final class LearningPlanProfileCapability
        implements DynamicNodeCapability<LearningPlanProfileCapability.NoArguments> {
    private static final DynamicCapabilityDescriptor DESCRIPTOR = new DynamicCapabilityDescriptor(
            "learning.profile.lookup", 1, DynamicEffectType.READ_ONLY,
            Map.of(), Set.of(), 1, true);

    private final LearningPlanNodeOperations operations;

    public LearningPlanProfileCapability(LearningPlanNodeOperations operations) {
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
        return operations.readProfile(context.owner());
    }

    record NoArguments() {
    }
}
