package io.github.imzmq.interview.agent.application.contract.execution.dynamic;

import java.util.List;
import java.util.Objects;

public record DynamicPlanValidationResult(
        ValidatedDynamicTaskPlan plan,
        List<DynamicPlanIssue> issues) {
    public DynamicPlanValidationResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
        if ((plan == null) == issues.isEmpty()) {
            throw new IllegalArgumentException("Validation requires either a plan or issues");
        }
    }

    public boolean valid() {
        return plan != null;
    }

    public static DynamicPlanValidationResult valid(ValidatedDynamicTaskPlan plan) {
        return new DynamicPlanValidationResult(Objects.requireNonNull(plan), List.of());
    }

    public static DynamicPlanValidationResult invalid(List<DynamicPlanIssue> issues) {
        return new DynamicPlanValidationResult(null, issues);
    }
}
