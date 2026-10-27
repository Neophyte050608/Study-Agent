package io.github.imzmq.interview.agent.application.contract.execution.dynamic;

import io.github.imzmq.interview.agent.application.contract.execution.DagNode;

import java.util.List;

/** Public application boundary for validating and compiling controlled dynamic plans. */
public interface DynamicPlanningOperations {
    List<DynamicCapabilityDescriptor> descriptors();

    DynamicPlanValidationResult validate(
            CandidateDynamicTaskPlan candidate,
            DynamicPlanScenarioPolicy policy,
            DynamicPlanningLimits limits);

    List<DagNode> compile(
            ValidatedDynamicTaskPlan plan,
            DynamicExecutionContext context);

    String digest(ValidatedDynamicTaskPlan plan);
}
