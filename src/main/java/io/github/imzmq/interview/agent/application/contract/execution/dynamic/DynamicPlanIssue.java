package io.github.imzmq.interview.agent.application.contract.execution.dynamic;

public record DynamicPlanIssue(String code, String nodeId) {
    public DynamicPlanIssue {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Issue code is required");
        }
    }
}
