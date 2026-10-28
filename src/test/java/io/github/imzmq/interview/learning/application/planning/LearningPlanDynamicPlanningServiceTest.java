package io.github.imzmq.interview.learning.application.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.agent.application.contract.execution.DagExecutionResult;
import io.github.imzmq.interview.agent.application.contract.execution.DagNode;
import io.github.imzmq.interview.agent.application.contract.execution.DagNodeStatus;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.CandidateDynamicTaskPlan;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicCapabilityDescriptor;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicEffectType;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicExecutionContext;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanObservationOperations;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanningOperations;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanningShadowOperations;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskPlan;
import io.github.imzmq.interview.agent.application.contract.extension.DynamicNodeCapability;
import io.github.imzmq.interview.agent.application.execution.FixedDagExecutor;
import io.github.imzmq.interview.agent.application.execution.dynamic.DefaultDynamicPlanningOperations;
import io.github.imzmq.interview.learning.application.contract.LearningProfileOperations;
import io.github.imzmq.interview.learning.application.contract.result.LearningPlanEvidence;
import io.github.imzmq.interview.learning.application.contract.result.TrainingProfileSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LearningPlanDynamicPlanningServiceTest {
    private static final DynamicExecutionContext CONTEXT =
            new DynamicExecutionContext("owner", "Java", "结合我的薄弱项安排训练");

    @Test
    void defaultPathPublishesTheRulePlanningLifecycle() {
        DynamicPlanObservationOperations observations = mock(DynamicPlanObservationOperations.class);
        Fixture fixture = fixture(new RuleBasedLearningPlanPlanner(), observations, false);

        assertThat(ids(fixture.service.nodes(CONTEXT))).containsExactly("profile", "compose");

        var order = inOrder(observations);
        order.verify(observations).requested("rules", "v1");
        order.verify(observations).generated(
                eq("rules"), eq("v1"), any(),
                org.mockito.ArgumentMatchers.matches("[0-9a-f]{64}"),
                org.mockito.ArgumentMatchers.longThat(value -> value >= 0));
        order.verify(observations).compiled(
                eq("rules"), eq("v1"), any(),
                org.mockito.ArgumentMatchers.matches("[0-9a-f]{64}"),
                org.mockito.ArgumentMatchers.longThat(value -> value >= 0));
        order.verifyNoMoreInteractions();
    }

    @Test
    void shadowComparisonIsControlledByAnIndependentSwitch() {
        Fixture disabled = fixture(new RuleBasedLearningPlanPlanner(),
                mock(DynamicPlanObservationOperations.class), false);
        Fixture enabled = fixture(new RuleBasedLearningPlanPlanner(),
                mock(DynamicPlanObservationOperations.class), true);

        assertThat(ids(disabled.service.nodes(CONTEXT))).containsExactly("profile", "compose");
        assertThat(ids(enabled.service.nodes(CONTEXT))).containsExactly("profile", "compose");

        verifyNoInteractions(disabled.shadowPlanning);
        verify(enabled.shadowPlanning).submit(
                eq(CONTEXT), any(ValidatedDynamicTaskPlan.class),
                any(LearningPlanScenarioPolicy.class),
                eq(enabled.dynamicPlanning.descriptors()), any());
    }

    @Test
    void shadowSubmissionFailureNeverChangesTheValidatedRuleGraph() {
        Fixture fixture = fixture(new RuleBasedLearningPlanPlanner(),
                mock(DynamicPlanObservationOperations.class), true);
        doThrow(new IllegalStateException("shadow unavailable"))
                .when(fixture.shadowPlanning).submit(any(), any(), any(), any(), any());

        assertThat(ids(fixture.service.nodes(CONTEXT))).containsExactly("profile", "compose");
    }

    @Test
    void invalidPlanPublishesStableRejectionAndFallsBackBeforeExecution() {
        DynamicPlanObservationOperations observations = mock(DynamicPlanObservationOperations.class);
        RuleBasedLearningPlanPlanner invalidPlanner = new RuleBasedLearningPlanPlanner() {
            @Override
            public CandidateDynamicTaskPlan plan(String topic, String request) {
                return new CandidateDynamicTaskPlan("1", List.of());
            }
        };
        Fixture fixture = fixture(invalidPlanner, observations, false);

        assertThat(ids(fixture.service.nodes(new DynamicExecutionContext(
                "secret-owner", "secret-topic", "raw request"))))
                .containsExactly("profile", "evidence", "compose");

        verify(observations).rejected(
                eq("rules"), eq("v1"), eq("PLAN_EMPTY"),
                org.mockito.ArgumentMatchers.longThat(value -> value >= 0));
        verify(observations).fallback("rules", "v1", "PLAN_EMPTY");
        verifyNoInteractions(fixture.shadowPlanning);
    }

    @Test
    void rulePlanCompilesOnlySelectedCapabilitiesAndOmittedCapabilityNeverExecutes() {
        AtomicInteger profileReads = new AtomicInteger();
        AtomicInteger evidenceReads = new AtomicInteger();
        Fixture fixture = fixture(new RuleBasedLearningPlanPlanner(), profileReads, evidenceReads,
                false, false, false);

        List<DagNode> nodes = fixture.service.nodes(CONTEXT);
        assertThat(ids(nodes)).containsExactly("profile", "compose");

        try (FixedDagExecutor executor = new FixedDagExecutor()) {
            DagExecutionResult result = executor.execute(nodes, Duration.ofSeconds(3));
            assertThat(result.code()).isEqualTo("COMPLETED");
            assertThat(result.nodes()).containsOnlyKeys("profile", "compose");
        }
        assertThat(profileReads).hasValue(1);
        assertThat(evidenceReads).hasValue(0);
    }

    @Test
    void plannerValidationAndCompilerFailuresFallBackBeforeExecution() {
        Fixture plannerFailure = fixture(new ThrowingPlanner());
        assertThat(ids(plannerFailure.service.nodes(CONTEXT)))
                .containsExactly("profile", "evidence", "compose");

        RuleBasedLearningPlanPlanner invalidPlanner = new RuleBasedLearningPlanPlanner() {
            @Override
            public CandidateDynamicTaskPlan plan(String topic, String request) {
                return new CandidateDynamicTaskPlan("1", List.of());
            }
        };
        Fixture invalid = fixture(invalidPlanner);
        assertThat(ids(invalid.service.nodes(CONTEXT)))
                .containsExactly("profile", "evidence", "compose");

        Fixture compileFailure = fixture(new RuleBasedLearningPlanPlanner(),
                new AtomicInteger(), new AtomicInteger(), true, false, false);
        assertThat(ids(compileFailure.service.nodes(CONTEXT)))
                .containsExactly("profile", "evidence", "compose");
    }

    @Test
    void planningBudgetExceededFallsBackBeforeExecution() {
        DynamicPlanObservationOperations observations = mock(DynamicPlanObservationOperations.class);
        Fixture base = fixture(new RuleBasedLearningPlanPlanner());
        DynamicPlanningOperations slowCompiler = mock(DynamicPlanningOperations.class);
        when(slowCompiler.validate(any(), any(), any())).thenAnswer(invocation ->
                base.dynamicPlanning.validate(invocation.getArgument(0), invocation.getArgument(1),
                        invocation.getArgument(2)));
        when(slowCompiler.digest(any())).thenAnswer(invocation ->
                base.dynamicPlanning.digest(invocation.getArgument(0)));
        when(slowCompiler.compile(any(), any())).thenAnswer(invocation -> {
            Thread.sleep(130);
            return base.dynamicPlanning.compile(invocation.getArgument(0), invocation.getArgument(1));
        });

        LearningPlanDynamicPlanningProperties properties = new LearningPlanDynamicPlanningProperties();
        properties.setPlannerTimeoutMillis(100);
        DynamicPlanningShadowOperations shadowPlanning = mock(DynamicPlanningShadowOperations.class);
        LearningPlanDynamicPlanningService service = new LearningPlanDynamicPlanningService(
                properties, base.fixedFactory, new RuleBasedLearningPlanPlanner(),
                slowCompiler, observations, shadowPlanning);

        assertThat(ids(service.nodes(CONTEXT)))
                .containsExactly("profile", "evidence", "compose");
        verify(observations).rejected(
                eq("rules"), eq("v1"), eq("PLANNER_TIMEOUT"),
                org.mockito.ArgumentMatchers.longThat(value -> value >= 100));
        verify(observations).fallback("rules", "v1", "PLANNER_TIMEOUT");
        verifyNoInteractions(shadowPlanning);
    }

    @Test
    void executionFailureDoesNotTriggerASecondFixedExecution() {
        AtomicInteger profileReads = new AtomicInteger();
        Fixture fixture = fixture(new RuleBasedLearningPlanPlanner(), profileReads,
                new AtomicInteger(), false, true, false);
        List<DagNode> nodes = fixture.service.nodes(CONTEXT);

        try (FixedDagExecutor executor = new FixedDagExecutor()) {
            DagExecutionResult result = executor.execute(nodes, Duration.ofSeconds(3));
            assertThat(result.nodes()).containsOnlyKeys("profile", "compose");
            assertThat(result.nodes().get("profile").status()).isEqualTo(DagNodeStatus.FAILED);
            assertThat(result.nodes().get("compose").status()).isEqualTo(DagNodeStatus.SUCCEEDED);
            assertThat(result.nodes().get("compose").output())
                    .isEqualTo(new LearningPlanComposeMaterial("plan"));
        }
        assertThat(profileReads).hasValue(1);
    }

    @Test
    void durableWorkflowAlwaysUsesTheFixedThreeNodeGraph() {
        Fixture fixture = fixture(new RuleBasedLearningPlanPlanner());
        LearningPlanPreviewService preview = new LearningPlanPreviewService(
                (nodes, deadline) -> { throw new AssertionError("not executed"); }, fixture.service);
        LearningPlanWorkflow workflow = new LearningPlanWorkflow(
                fixture.fixedFactory, preview, new ObjectMapper());

        assertThat(ids(workflow.nodes("owner", "{\"topic\":\"Java\",\"request\":\"history\"}")))
                .containsExactly("profile", "evidence", "compose");
    }

    @Test
    void capabilityDescriptorsRemainExact() {
        Fixture fixture = fixture(new RuleBasedLearningPlanPlanner());
        Map<String, DynamicCapabilityDescriptor> descriptors = fixture.dynamicPlanning.descriptors().stream()
                .collect(java.util.stream.Collectors.toMap(
                        DynamicCapabilityDescriptor::capabilityId, value -> value));

        assertThat(descriptors.get("learning.profile.lookup"))
                .extracting(DynamicCapabilityDescriptor::capabilityVersion,
                        DynamicCapabilityDescriptor::effectType,
                        DynamicCapabilityDescriptor::estimatedCost,
                        DynamicCapabilityDescriptor::requireSuccessfulDependencies)
                .containsExactly(1, DynamicEffectType.READ_ONLY, 1, true);
        assertThat(descriptors.get("knowledge.evidence.retrieve").arguments().get("limit").defaultValue())
                .isEqualTo(4);
        assertThat(descriptors.get("learning.plan.compose").allowedDependencyCapabilities())
                .containsExactlyInAnyOrder("learning.profile.lookup", "knowledge.evidence.retrieve");
        assertThat(descriptors.get("learning.plan.compose").requireSuccessfulDependencies()).isFalse();
    }

    private static Fixture fixture(RuleBasedLearningPlanPlanner planner) {
        return fixture(planner, new AtomicInteger(), new AtomicInteger(), false, false, false);
    }

    private static Fixture fixture(
            RuleBasedLearningPlanPlanner planner,
            DynamicPlanObservationOperations observations,
            boolean shadowEnabled) {
        Fixture base = fixture(planner, new AtomicInteger(), new AtomicInteger(),
                false, false, shadowEnabled);
        return createFixture(planner, base.dynamicPlanning, base.fixedFactory,
                observations, shadowEnabled);
    }

    private static Fixture fixture(
            RuleBasedLearningPlanPlanner planner,
            AtomicInteger profileReads,
            AtomicInteger evidenceReads,
            boolean failCompile,
            boolean failProfile,
            boolean shadowEnabled) {
        LearningProfileOperations profiles = mock(LearningProfileOperations.class);
        if (failProfile) {
            when(profiles.snapshot("owner")).thenAnswer(invocation -> {
                profileReads.incrementAndGet();
                throw new IllegalStateException("private");
            });
        } else {
            when(profiles.snapshot("owner")).thenAnswer(invocation -> {
                profileReads.incrementAndGet();
                return new TrainingProfileSnapshot(List.of(), List.of(), "UP",
                        List.of("Java"), List.of(), 1, "now");
            });
        }
        LearningPlanNodeOperations operations = new LearningPlanNodeOperations(
                profiles,
                topic -> {
                    evidenceReads.incrementAndGet();
                    return List.of(new LearningPlanEvidence("doc", "title", "excerpt"));
                },
                input -> "plan");
        LearningPlanFixedNodeFactory fixedFactory = new LearningPlanFixedNodeFactory(operations);
        List<DynamicNodeCapability<?>> capabilities = List.of(
                new LearningPlanProfileCapability(operations),
                new LearningPlanEvidenceCapability(operations),
                new LearningPlanComposeCapability(operations));
        DynamicPlanningOperations dynamicPlanning = new DefaultDynamicPlanningOperations(capabilities);
        if (failCompile) {
            DynamicPlanningOperations delegate = dynamicPlanning;
            dynamicPlanning = mock(DynamicPlanningOperations.class);
            when(dynamicPlanning.descriptors()).thenReturn(delegate.descriptors());
            when(dynamicPlanning.validate(any(), any(), any())).thenAnswer(invocation ->
                    delegate.validate(invocation.getArgument(0), invocation.getArgument(1),
                            invocation.getArgument(2)));
            when(dynamicPlanning.digest(any())).thenAnswer(invocation ->
                    delegate.digest(invocation.getArgument(0)));
            when(dynamicPlanning.compile(any(), any()))
                    .thenThrow(new IllegalArgumentException("PLAN_COMPILE_FAILED"));
        }
        return createFixture(planner, dynamicPlanning, fixedFactory,
                mock(DynamicPlanObservationOperations.class), shadowEnabled);
    }

    private static Fixture createFixture(
            RuleBasedLearningPlanPlanner planner,
            DynamicPlanningOperations dynamicPlanning,
            LearningPlanFixedNodeFactory fixedFactory,
            DynamicPlanObservationOperations observations,
            boolean shadowEnabled) {
        LearningPlanDynamicPlanningProperties properties = new LearningPlanDynamicPlanningProperties();
        properties.setShadowEnabled(shadowEnabled);
        DynamicPlanningShadowOperations shadowPlanning = mock(DynamicPlanningShadowOperations.class);
        LearningPlanDynamicPlanningService service = new LearningPlanDynamicPlanningService(
                properties, fixedFactory, planner, dynamicPlanning, observations, shadowPlanning);
        return new Fixture(service, fixedFactory, dynamicPlanning, shadowPlanning);
    }

    private static List<String> ids(List<DagNode> nodes) {
        return nodes.stream().map(DagNode::id).toList();
    }

    private static final class ThrowingPlanner extends RuleBasedLearningPlanPlanner {
        @Override
        public CandidateDynamicTaskPlan plan(String topic, String request) {
            throw new IllegalStateException("private planner detail");
        }
    }

    private record Fixture(
            LearningPlanDynamicPlanningService service,
            LearningPlanFixedNodeFactory fixedFactory,
            DynamicPlanningOperations dynamicPlanning,
            DynamicPlanningShadowOperations shadowPlanning) {
    }
}
