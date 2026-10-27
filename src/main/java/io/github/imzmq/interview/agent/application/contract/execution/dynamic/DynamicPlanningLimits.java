package io.github.imzmq.interview.agent.application.contract.execution.dynamic;

public record DynamicPlanningLimits(
        int maxNodes,
        int maxDepth,
        int maxDependenciesPerNode,
        int maxFanOut,
        int maxEstimatedCost,
        int maxIssues) {
    public DynamicPlanningLimits {
        if (maxNodes <= 0 || maxDepth <= 0 || maxDependenciesPerNode <= 0
                || maxFanOut <= 0 || maxEstimatedCost <= 0 || maxIssues <= 0) {
            throw new IllegalArgumentException("Dynamic planning limits must be positive");
        }
        if (maxIssues > 20) {
            throw new IllegalArgumentException("Dynamic planning issues cannot exceed 20");
        }
    }
}
