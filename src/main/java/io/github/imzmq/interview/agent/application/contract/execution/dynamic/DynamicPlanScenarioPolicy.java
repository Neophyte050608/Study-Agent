package io.github.imzmq.interview.agent.application.contract.execution.dynamic;

import java.util.List;
import java.util.Set;

public interface DynamicPlanScenarioPolicy {
    String scenarioId();

    Set<String> allowedCapabilities();

    List<DynamicPlanIssue> validate(ValidatedDynamicTaskPlan plan);
}
