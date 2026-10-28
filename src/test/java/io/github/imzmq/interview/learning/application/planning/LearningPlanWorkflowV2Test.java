package io.github.imzmq.interview.learning.application.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.agent.application.contract.execution.DagNode;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicExecutionContext;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanningOperations;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskNode;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LearningPlanWorkflowV2Test {
    private static final String INPUT = "{\"topic\":\"Java\",\"request\":\"history\"}";
    private static final String DIGEST = "b".repeat(64);
    private static final DynamicExecutionContext CONTEXT =
            new DynamicExecutionContext("owner", "Java", "history");

    @Test
    void preparesOnceAndPersistsTheFinalCapabilityGraph() {
        LearningPlanDynamicPlanningService planning = mock(LearningPlanDynamicPlanningService.class);
        DynamicPlanningOperations dynamicPlanning = mock(DynamicPlanningOperations.class);
        LearningPlanPreparedGraph prepared = preparedGraph(false);
        when(planning.prepare(CONTEXT)).thenReturn(prepared);
        LearningPlanWorkflowV2 workflow = workflow(planning, dynamicPlanning);

        var result = workflow.prepare("owner", INPUT);

        assertThat(result.nodes()).isEqualTo(prepared.nodes());
        assertThat(result.definitionJson()).contains("\"workflowVersion\":2");
        verify(planning).prepare(CONTEXT);
        verifyNoInteractions(dynamicPlanning);
    }

    @Test
    void restoresThePersistedGraphWithoutPlanningAgain() {
        LearningPlanDynamicPlanningService planning = mock(LearningPlanDynamicPlanningService.class);
        DynamicPlanningOperations dynamicPlanning = mock(DynamicPlanningOperations.class);
        LearningPlanPreparedGraph prepared = preparedGraph(false);
        LearningPlanWorkflowV2 workflow = workflow(planning, dynamicPlanning);
        String definition = codec().encode(prepared);
        when(dynamicPlanning.digest(org.mockito.ArgumentMatchers.any())).thenReturn(DIGEST);
        when(dynamicPlanning.compile(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(CONTEXT))).thenReturn(prepared.nodes());

        List<DagNode> restored = workflow.restore("owner", INPUT, definition);

        assertThat(restored).isEqualTo(prepared.nodes());
        verifyNoInteractions(planning);
        verify(dynamicPlanning).compile(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(CONTEXT));
    }

    @Test
    void rejectsDigestPolicyCapabilityAndSuccessPolicyDriftWithoutReplanning() {
        LearningPlanDynamicPlanningService planning = mock(LearningPlanDynamicPlanningService.class);
        DynamicPlanningOperations dynamicPlanning = mock(DynamicPlanningOperations.class);
        LearningPlanPreparedGraph prepared = preparedGraph(false);
        LearningPlanWorkflowV2 workflow = workflow(planning, dynamicPlanning);
        String definition = codec().encode(prepared);

        when(dynamicPlanning.digest(org.mockito.ArgumentMatchers.any())).thenReturn("c".repeat(64));
        assertMismatch(() -> workflow.restore("owner", INPUT, definition));

        ValidatedDynamicTaskNode composeOnly = new ValidatedDynamicTaskNode(
                "compose", "learning.plan.compose", 1, List.of(), Map.of());
        LearningPlanPreparedGraph invalidScenario = new LearningPlanPreparedGraph(
                new ValidatedDynamicTaskPlan("1", "learning-plan-preview", List.of(composeOnly), 1),
                DIGEST,
                List.of(new DagNode("compose", Set.of(), false, ignored -> "plan")),
                LearningPlanPreparedGraph.Source.RULES,
                null);
        assertMismatch(() -> workflow.restore("owner", INPUT, codec().encode(invalidScenario)));

        when(dynamicPlanning.digest(org.mockito.ArgumentMatchers.any())).thenReturn(DIGEST);
        when(dynamicPlanning.compile(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(CONTEXT)))
                .thenThrow(new IllegalArgumentException("PLAN_COMPILE_FAILED"));
        assertThatThrownBy(() -> workflow.restore("owner", INPUT, definition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("PLAN_COMPILE_FAILED");

        when(dynamicPlanning.compile(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(CONTEXT))).thenReturn(preparedGraph(true).nodes());
        assertMismatch(() -> workflow.restore("owner", INPUT, definition));
        verifyNoInteractions(planning);
    }

    @Test
    void rejectsUntrustedOrMalformedInputBeforePlanningOrCompilation() {
        LearningPlanDynamicPlanningService planning = mock(LearningPlanDynamicPlanningService.class);
        DynamicPlanningOperations dynamicPlanning = mock(DynamicPlanningOperations.class);
        LearningPlanWorkflowV2 workflow = workflow(planning, dynamicPlanning);

        assertThatThrownBy(() -> workflow.prepare(
                "owner", "{\"topic\":\"Java\",\"userId\":\"other\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_PREVIEW_INPUT");
        assertThatThrownBy(() -> workflow.restore(
                "owner", "{\"topic\":\"Java\",\"request\":\"x\"} trailing", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_PREVIEW_INPUT");
        verifyNoInteractions(planning, dynamicPlanning);
    }

    private static LearningPlanWorkflowV2 workflow(
            LearningPlanDynamicPlanningService planning,
            DynamicPlanningOperations dynamicPlanning) {
        return new LearningPlanWorkflowV2(
                planning, dynamicPlanning, codec(),
                mock(LearningPlanWorkflowOutputCodec.class), new ObjectMapper());
    }

    private static LearningPlanWorkflowSnapshotCodec codec() {
        return new LearningPlanWorkflowSnapshotCodec(new ObjectMapper());
    }

    private static LearningPlanPreparedGraph preparedGraph(boolean requireComposeSuccess) {
        var profile = new ValidatedDynamicTaskNode(
                "profile", "learning.profile.lookup", 1, List.of(), Map.of());
        var compose = new ValidatedDynamicTaskNode(
                "compose", "learning.plan.compose", 1, List.of("profile"), Map.of());
        var plan = new ValidatedDynamicTaskPlan(
                "1", "learning-plan-preview", List.of(profile, compose), 2);
        List<DagNode> nodes = List.of(
                new DagNode("profile", Set.of(), true, ignored -> "profile"),
                new DagNode("compose", Set.of("profile"), requireComposeSuccess, ignored -> "plan"));
        return new LearningPlanPreparedGraph(
                plan, DIGEST, nodes, LearningPlanPreparedGraph.Source.RULES, null);
    }

    private static void assertMismatch(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("WORKFLOW_DEFINITION_MISMATCH");
    }
}
