package io.github.imzmq.interview.learning.application.planning;

import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanningLimits;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConfigurationProperties(prefix = "app.learning.plan-preview.dynamic-planning")
public class LearningPlanDynamicPlanningProperties {
    private boolean shadowEnabled;
    private int plannerTimeoutMillis = 2000;
    private int maxNodes = 8;
    private int maxDepth = 4;
    private int maxDependenciesPerNode = 4;
    private int maxFanOut = 4;
    private int maxEstimatedCost = 10;
    private int maxIssues = 20;

    @PostConstruct
    public void validate() {
        if (plannerTimeoutMillis < 100 || plannerTimeoutMillis > 3000
                || maxNodes < 2 || maxNodes > 8
                || maxDepth < 1 || maxDepth > 4
                || maxDependenciesPerNode < 1 || maxDependenciesPerNode > 4
                || maxFanOut < 1 || maxFanOut > 4
                || maxEstimatedCost < 1 || maxEstimatedCost > 10
                || maxIssues < 1 || maxIssues > 20) {
            throw new IllegalArgumentException("INVALID_DYNAMIC_PLANNING_LIMITS");
        }
    }

    public DynamicPlanningLimits toLimits() {
        return new DynamicPlanningLimits(
                maxNodes,
                maxDepth,
                maxDependenciesPerNode,
                maxFanOut,
                maxEstimatedCost,
                maxIssues);
    }

    public boolean isShadowEnabled() {
        return shadowEnabled;
    }

    public void setShadowEnabled(boolean shadowEnabled) {
        this.shadowEnabled = shadowEnabled;
    }

    public int getPlannerTimeoutMillis() {
        return plannerTimeoutMillis;
    }

    public void setPlannerTimeoutMillis(int plannerTimeoutMillis) {
        this.plannerTimeoutMillis = plannerTimeoutMillis;
    }

    public int getMaxNodes() {
        return maxNodes;
    }

    public void setMaxNodes(int maxNodes) {
        this.maxNodes = maxNodes;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public void setMaxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    public int getMaxDependenciesPerNode() {
        return maxDependenciesPerNode;
    }

    public void setMaxDependenciesPerNode(int maxDependenciesPerNode) {
        this.maxDependenciesPerNode = maxDependenciesPerNode;
    }

    public int getMaxFanOut() {
        return maxFanOut;
    }

    public void setMaxFanOut(int maxFanOut) {
        this.maxFanOut = maxFanOut;
    }

    public int getMaxEstimatedCost() {
        return maxEstimatedCost;
    }

    public void setMaxEstimatedCost(int maxEstimatedCost) {
        this.maxEstimatedCost = maxEstimatedCost;
    }

    public int getMaxIssues() {
        return maxIssues;
    }

    public void setMaxIssues(int maxIssues) {
        this.maxIssues = maxIssues;
    }
}
