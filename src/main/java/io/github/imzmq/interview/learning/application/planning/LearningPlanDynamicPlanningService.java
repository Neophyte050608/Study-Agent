package io.github.imzmq.interview.learning.application.planning;

import io.github.imzmq.interview.agent.application.contract.execution.DagNode;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.CandidateDynamicTaskPlan;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicExecutionContext;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanValidationResult;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanningOperations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public final class LearningPlanDynamicPlanningService {
    private final LearningPlanDynamicPlanningProperties properties;
    private final LearningPlanFixedNodeFactory fixedNodeFactory;
    private final RuleBasedLearningPlanPlanner planner;
    private final DynamicPlanningOperations dynamicPlanning;
    private final LearningPlanScenarioPolicy policy;

    @Autowired
    public LearningPlanDynamicPlanningService(
            LearningPlanDynamicPlanningProperties properties,
            LearningPlanFixedNodeFactory fixedNodeFactory,
            RuleBasedLearningPlanPlanner planner,
            DynamicPlanningOperations dynamicPlanning) {
        this(properties, fixedNodeFactory, planner, dynamicPlanning,
                new LearningPlanScenarioPolicy());
    }

    LearningPlanDynamicPlanningService(
            LearningPlanDynamicPlanningProperties properties,
            LearningPlanFixedNodeFactory fixedNodeFactory,
            RuleBasedLearningPlanPlanner planner,
            DynamicPlanningOperations dynamicPlanning,
            LearningPlanScenarioPolicy policy) {
        this.properties = Objects.requireNonNull(properties);
        this.fixedNodeFactory = Objects.requireNonNull(fixedNodeFactory);
        this.planner = Objects.requireNonNull(planner);
        this.dynamicPlanning = Objects.requireNonNull(dynamicPlanning);
        this.policy = Objects.requireNonNull(policy);
    }

    public List<DagNode> nodes(DynamicExecutionContext context) {
        Objects.requireNonNull(context, "Dynamic execution context is required");
        if (properties.getMode() == LearningPlanDynamicPlanningProperties.Mode.FIXED) {
            return fixedNodes(context);
        }
        try {
            CandidateDynamicTaskPlan candidate = planner.plan(context.topic(), context.request());
            DynamicPlanValidationResult result = dynamicPlanning.validate(
                    candidate, policy, properties.toLimits());
            if (!result.valid()) {
                return fixedNodes(context);
            }
            return dynamicPlanning.compile(result.plan(), context);
        } catch (RuntimeException exception) {
            return fixedNodes(context);
        }
    }

    private List<DagNode> fixedNodes(DynamicExecutionContext context) {
        return fixedNodeFactory.nodes(context.owner(), context.topic(), context.request());
    }
}
