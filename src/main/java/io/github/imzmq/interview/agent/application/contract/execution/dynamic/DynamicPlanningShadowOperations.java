package io.github.imzmq.interview.agent.application.contract.execution.dynamic;

import java.util.List;

/** Non-executing comparison boundary for optional dynamic-plan shadow evaluation. */
public interface DynamicPlanningShadowOperations {
    void submit(
            DynamicExecutionContext context,
            ValidatedDynamicTaskPlan rulePlan,
            DynamicPlanScenarioPolicy policy,
            List<DynamicCapabilityDescriptor> descriptors,
            DynamicPlanningLimits limits);
}
