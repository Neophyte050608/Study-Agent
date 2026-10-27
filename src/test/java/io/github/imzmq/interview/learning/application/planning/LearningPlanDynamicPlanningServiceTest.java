package io.github.imzmq.interview.learning.application.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.agent.application.contract.execution.DagExecutionResult;
import io.github.imzmq.interview.agent.application.contract.execution.DagNode;
import io.github.imzmq.interview.agent.application.contract.execution.DagNodeStatus;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.CandidateDynamicTaskPlan;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicCapabilityDescriptor;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicEffectType;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicExecutionContext;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanningOperations;
import io.github.imzmq.interview.agent.application.execution.FixedDagExecutor;
import io.github.imzmq.interview.agent.application.contract.extension.DynamicNodeCapability;
import io.github.imzmq.interview.agent.application.execution.dynamic.DefaultDynamicPlanningOperations;
import io.github.imzmq.interview.learning.application.contract.LearningProfileOperations;
import io.github.imzmq.interview.learning.application.contract.result.LearningPlanEvidence;
import io.github.imzmq.interview.learning.application.contract.result.TrainingProfileSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LearningPlanDynamicPlanningServiceTest {
    private static final DynamicExecutionContext CONTEXT =
            new DynamicExecutionContext("owner", "Java", "结合我的薄弱项安排训练");

    @Test
    void fixedModeReturnsTheFixedGraphWithoutInvokingThePlanner() {
        DynamicPlanningOperations dynamicPlanning = mock(DynamicPlanningOperations.class);
        Fixture fixture = fixedFixture(new ThrowingPlanner(), dynamicPlanning);

        assertThat(ids(fixture.service.nodes(CONTEXT)))
                .containsExactly("profile", "evidence", "compose");
        verifyNoInteractions(dynamicPlanning);
    }

    @Test
    void rulesModeCompilesOnlySelectedCapabilitiesAndOmittedCapabilityNeverExecutes() {
        AtomicInteger profileReads = new AtomicInteger();
        AtomicInteger evidenceReads = new AtomicInteger();
        Fixture fixture = fixture(LearningPlanDynamicPlanningProperties.Mode.RULES,
                new RuleBasedLearningPlanPlanner(), profileReads, evidenceReads, false);

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
        Fixture plannerFailure = fixture(LearningPlanDynamicPlanningProperties.Mode.RULES,
                new ThrowingPlanner());
        assertThat(ids(plannerFailure.service.nodes(CONTEXT)))
                .containsExactly("profile", "evidence", "compose");

        RuleBasedLearningPlanPlanner invalidPlanner = new RuleBasedLearningPlanPlanner() {
            @Override
            public CandidateDynamicTaskPlan plan(String topic, String request) {
                return new CandidateDynamicTaskPlan("1", List.of());
            }
        };
        Fixture invalid = fixture(LearningPlanDynamicPlanningProperties.Mode.RULES, invalidPlanner);
        assertThat(ids(invalid.service.nodes(CONTEXT)))
                .containsExactly("profile", "evidence", "compose");

        Fixture compileFailure = fixture(LearningPlanDynamicPlanningProperties.Mode.RULES,
                new RuleBasedLearningPlanPlanner(), new AtomicInteger(), new AtomicInteger(), true);
        assertThat(ids(compileFailure.service.nodes(CONTEXT)))
                .containsExactly("profile", "evidence", "compose");
    }

    @Test
    void executionFailureDoesNotTriggerASecondFixedExecution() {
        AtomicInteger profileReads = new AtomicInteger();
        Fixture fixture = fixture(LearningPlanDynamicPlanningProperties.Mode.RULES,
                new RuleBasedLearningPlanPlanner(), profileReads, new AtomicInteger(), false,
                true);
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
    void durableWorkflowAlwaysUsesTheFixedThreeNodeGraphEvenInRulesMode() {
        Fixture fixture = fixture(LearningPlanDynamicPlanningProperties.Mode.RULES,
                new RuleBasedLearningPlanPlanner());
        LearningPlanPreviewService preview = new LearningPlanPreviewService(
                (nodes, deadline) -> { throw new AssertionError("not executed"); }, fixture.service);
        LearningPlanWorkflow workflow = new LearningPlanWorkflow(
                fixture.fixedFactory, preview, new ObjectMapper());

        assertThat(ids(workflow.nodes("owner", "{\"topic\":\"Java\",\"request\":\"history\"}")))
                .containsExactly("profile", "evidence", "compose");
    }

    @Test
    void capabilityDescriptorsAreExactAndComposeOnlyDegradesSelectedFailedSources() {
        Fixture fixture = fixture(LearningPlanDynamicPlanningProperties.Mode.RULES,
                new RuleBasedLearningPlanPlanner());
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

    private static Fixture fixture(
            LearningPlanDynamicPlanningProperties.Mode mode,
            RuleBasedLearningPlanPlanner planner) {
        return fixture(mode, planner, new AtomicInteger(), new AtomicInteger(), false);
    }

    private static Fixture fixture(
            LearningPlanDynamicPlanningProperties.Mode mode,
            RuleBasedLearningPlanPlanner planner,
            AtomicInteger profileReads,
            AtomicInteger evidenceReads,
            boolean failCompile) {
        return fixture(mode, planner, profileReads, evidenceReads, failCompile, false);
    }

    private static Fixture fixture(
            LearningPlanDynamicPlanningProperties.Mode mode,
            RuleBasedLearningPlanPlanner planner,
            AtomicInteger profileReads,
            AtomicInteger evidenceReads,
            boolean failCompile,
            boolean failProfile) {
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
            when(dynamicPlanning.compile(any(), any()))
                    .thenThrow(new IllegalArgumentException("PLAN_COMPILE_FAILED"));
        }
        return fixture(mode, planner, dynamicPlanning, fixedFactory);
    }

    private static Fixture fixedFixture(
            RuleBasedLearningPlanPlanner planner,
            DynamicPlanningOperations dynamicPlanning) {
        LearningPlanNodeOperations operations = new LearningPlanNodeOperations(
                mock(LearningProfileOperations.class), topic -> List.of(), input -> "plan");
        return fixture(LearningPlanDynamicPlanningProperties.Mode.FIXED, planner,
                dynamicPlanning, new LearningPlanFixedNodeFactory(operations));
    }

    private static Fixture fixture(
            LearningPlanDynamicPlanningProperties.Mode mode,
            RuleBasedLearningPlanPlanner planner,
            DynamicPlanningOperations dynamicPlanning,
            LearningPlanFixedNodeFactory fixedFactory) {
        LearningPlanDynamicPlanningProperties properties = new LearningPlanDynamicPlanningProperties();
        properties.setMode(mode);
        LearningPlanDynamicPlanningService service = new LearningPlanDynamicPlanningService(
                properties, fixedFactory, planner, dynamicPlanning);
        return new Fixture(service, fixedFactory, dynamicPlanning);
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
            DynamicPlanningOperations dynamicPlanning) {
    }
}
