package io.github.imzmq.interview.agent.infrastructure.planning;

import io.github.imzmq.interview.agent.application.contract.execution.dynamic.CandidateDynamicTaskPlan;

import java.util.Objects;

public record DynamicPlanParseResult(CandidateDynamicTaskPlan plan, String errorCode) {
    public DynamicPlanParseResult {
        if ((plan == null) == (errorCode == null || errorCode.isBlank())) {
            throw new IllegalArgumentException("Parse result requires either a plan or an error code");
        }
    }

    public boolean success() {
        return plan != null;
    }

    public static DynamicPlanParseResult success(CandidateDynamicTaskPlan plan) {
        return new DynamicPlanParseResult(Objects.requireNonNull(plan), "");
    }

    public static DynamicPlanParseResult failure(String code) {
        return new DynamicPlanParseResult(null, code);
    }
}
