package io.github.imzmq.interview.learning.application.planning;

import io.github.imzmq.interview.agent.application.contract.execution.DagNode;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.CandidateDynamicTaskPlan;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicExecutionContext;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanObservationOperations;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanIssue;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanValidationResult;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanningOperations;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanningShadowOperations;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskPlan;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public final class LearningPlanDynamicPlanningService {
    private final LearningPlanDynamicPlanningProperties properties;
    private final RuleBasedLearningPlanPlanner planner;
    private final DynamicPlanningOperations dynamicPlanning;
    private final DynamicPlanObservationOperations observations;
    private final LearningPlanScenarioPolicy policy = new LearningPlanScenarioPolicy();
    private final DynamicPlanningShadowOperations shadowPlanning;

    private static final String PLANNER_TYPE = "rules";
    private static final String PLANNER_VERSION = "v1";
    private static final Set<String> STABLE_ERROR_CODES = Set.of(
            "UNSUPPORTED_SCHEMA_VERSION", "PLAN_EMPTY", "PLAN_TOO_LARGE",
            "INVALID_NODE_ID", "DUPLICATE_NODE_ID", "UNKNOWN_CAPABILITY",
            "INVALID_ARGUMENTS", "UNKNOWN_DEPENDENCY", "CYCLIC_DEPENDENCY",
            "MAX_DEPTH_EXCEEDED", "MAX_DEPENDENCIES_EXCEEDED", "MAX_FAN_OUT_EXCEEDED",
            "DISALLOWED_EFFECT", "INCOMPATIBLE_DEPENDENCY", "MISSING_FINAL_OUTPUT",
            "SCENARIO_POLICY_VIOLATION", "PLAN_BUDGET_EXCEEDED", "PLAN_COMPILE_FAILED",
            "PLANNER_TIMEOUT", "PLANNER_UNAVAILABLE");

    public LearningPlanDynamicPlanningService(
            LearningPlanDynamicPlanningProperties properties,
            RuleBasedLearningPlanPlanner planner,
            DynamicPlanningOperations dynamicPlanning,
            DynamicPlanObservationOperations observations,
            DynamicPlanningShadowOperations shadowPlanning) {
        this.properties = Objects.requireNonNull(properties);
        this.planner = Objects.requireNonNull(planner);
        this.dynamicPlanning = Objects.requireNonNull(dynamicPlanning);
        this.observations = Objects.requireNonNull(observations);
        this.shadowPlanning = Objects.requireNonNull(shadowPlanning);
    }

    public List<DagNode> nodes(DynamicExecutionContext context) {
        return prepare(context).nodes();
    }

    LearningPlanPreparedGraph prepare(DynamicExecutionContext context) {
        Objects.requireNonNull(context, "Dynamic execution context is required");
        long startedAt = System.nanoTime();
        long deadline = startedAt + Duration.ofMillis(properties.getPlannerTimeoutMillis()).toNanos();
        observations.requested(PLANNER_TYPE, PLANNER_VERSION);
        String fallbackCode;
        try {
            CandidateDynamicTaskPlan candidate = planner.plan(context.topic(), context.request());
            ensureWithinPlanningBudget(deadline);
            DynamicPlanValidationResult result = dynamicPlanning.validate(
                    candidate, policy, properties.toLimits());
            ensureWithinPlanningBudget(deadline);
            if (!result.valid()) {
                fallbackCode = firstIssue(result);
            } else {
                ValidatedDynamicTaskPlan plan = result.plan();
                String digest = dynamicPlanning.digest(plan);
                observations.generated(PLANNER_TYPE, PLANNER_VERSION,
                        plan, digest, elapsedMillis(startedAt));
                List<DagNode> nodes = dynamicPlanning.compile(plan, context);
                ensureWithinPlanningBudget(deadline);
                observations.compiled(PLANNER_TYPE, PLANNER_VERSION,
                        plan, digest, elapsedMillis(startedAt));
                submitShadowIfEnabled(context, plan);
                return new LearningPlanPreparedGraph(
                        plan, digest, nodes, LearningPlanPreparedGraph.Source.RULES, null);
            }
        } catch (RuntimeException exception) {
            fallbackCode = stableExceptionCode(exception);
        }

        observations.rejected(PLANNER_TYPE, PLANNER_VERSION,
                fallbackCode, elapsedMillis(startedAt));
        observations.fallback(PLANNER_TYPE, PLANNER_VERSION, fallbackCode);
        return prepareFallback(context, fallbackCode);
    }

    private LearningPlanPreparedGraph prepareFallback(
            DynamicExecutionContext context,
            String fallbackCode) {
        try {
            DynamicPlanValidationResult result = dynamicPlanning.validate(
                    planner.fixedFallback(), policy, properties.toLimits());
            if (!result.valid()) {
                throw new IllegalArgumentException(firstIssue(result));
            }
            ValidatedDynamicTaskPlan plan = result.plan();
            String digest = dynamicPlanning.digest(plan);
            List<DagNode> nodes = dynamicPlanning.compile(plan, context);
            return new LearningPlanPreparedGraph(
                    plan, digest, nodes,
                    LearningPlanPreparedGraph.Source.FIXED_FALLBACK, fallbackCode);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(stableExceptionCode(exception), exception);
        }
    }

    private static void ensureWithinPlanningBudget(long deadlineNanos) {
        if (System.nanoTime() > deadlineNanos) {
            throw new IllegalArgumentException("PLANNER_TIMEOUT");
        }
    }

    private void submitShadowIfEnabled(
            DynamicExecutionContext context,
            ValidatedDynamicTaskPlan rulePlan) {
        if (!properties.isShadowEnabled()) {
            return;
        }
        try {
            shadowPlanning.submit(
                    context,
                    rulePlan,
                    policy,
                    dynamicPlanning.descriptors(),
                    properties.toLimits());
        } catch (RuntimeException ignored) {
            // Shadow comparison must never affect the validated rule-plan result.
        }
    }

    private static String firstIssue(DynamicPlanValidationResult result) {
        return result.issues().stream()
                .findFirst()
                .map(DynamicPlanIssue::code)
                .orElse("PLANNING_FAILED");
    }

    private static String stableExceptionCode(RuntimeException exception) {
        String message = exception.getMessage();
        if (STABLE_ERROR_CODES.contains(message)) {
            return message;
        }
        return "PLANNING_FAILED";
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }
}
