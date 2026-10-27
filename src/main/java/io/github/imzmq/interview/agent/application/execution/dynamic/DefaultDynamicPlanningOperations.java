package io.github.imzmq.interview.agent.application.execution.dynamic;

import io.github.imzmq.interview.agent.application.contract.execution.DagNode;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.CandidateDynamicTaskPlan;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicCapabilityDescriptor;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicExecutionContext;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanScenarioPolicy;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanValidationResult;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanningLimits;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanningOperations;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskPlan;
import io.github.imzmq.interview.agent.application.contract.extension.DynamicNodeCapability;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public final class DefaultDynamicPlanningOperations implements DynamicPlanningOperations {
    private final DynamicCapabilityRegistry registry;
    private final DynamicTaskPlanValidator validator;
    private final DynamicTaskPlanCompiler compiler;
    private final DynamicPlanFingerprint fingerprint = new DynamicPlanFingerprint();

    public DefaultDynamicPlanningOperations(List<DynamicNodeCapability<?>> capabilities) {
        this.registry = new DynamicCapabilityRegistry(capabilities);
        this.validator = new DynamicTaskPlanValidator(registry.descriptors());
        this.compiler = new DynamicTaskPlanCompiler(registry);
    }

    @Override
    public List<DynamicCapabilityDescriptor> descriptors() {
        return registry.descriptors();
    }

    @Override
    public DynamicPlanValidationResult validate(
            CandidateDynamicTaskPlan candidate,
            DynamicPlanScenarioPolicy policy,
            DynamicPlanningLimits limits) {
        return validator.validate(candidate, policy, limits);
    }

    @Override
    public List<DagNode> compile(
            ValidatedDynamicTaskPlan plan,
            DynamicExecutionContext context) {
        return compiler.compile(plan, context);
    }

    @Override
    public String digest(ValidatedDynamicTaskPlan plan) {
        return fingerprint.digest(plan);
    }
}
