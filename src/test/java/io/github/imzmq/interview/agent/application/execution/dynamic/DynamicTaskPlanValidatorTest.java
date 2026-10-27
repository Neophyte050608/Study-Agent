package io.github.imzmq.interview.agent.application.execution.dynamic;

import io.github.imzmq.interview.agent.application.contract.execution.dynamic.CandidateDynamicTaskNode;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.CandidateDynamicTaskPlan;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicArgumentDefinition;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicArgumentType;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicCapabilityDescriptor;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicEffectType;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanIssue;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanScenarioPolicy;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanValidationResult;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanningLimits;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskNode;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicTaskPlanValidatorTest {

    private final DynamicCapabilityDescriptor source = descriptor(
            "source.read", Set.of(), Map.of(), 1, true);
    private final DynamicCapabilityDescriptor compose = descriptor(
            "result.compose", Set.of("source.read"), Map.of(), 2, false);
    private final DynamicPlanningLimits limits = new DynamicPlanningLimits(8, 4, 4, 4, 10, 20);
    private final DynamicPlanScenarioPolicy policy = policyAllowing("source.read", "result.compose");

    @Test
    void normalizesDefaultsAndProducesAnImmutableValidatedPlan() {
        DynamicCapabilityDescriptor parameterizedSource = descriptor(
                "source.read",
                Set.of(),
                Map.of("limit", new DynamicArgumentDefinition(
                        "limit", DynamicArgumentType.INTEGER, false, 3, 1L, 5L)),
                1,
                true);
        CandidateDynamicTaskPlan candidate = plan(
                node("source", "source.read", List.of(), Map.of()),
                node("compose", "result.compose", List.of("source"), Map.of()));

        DynamicPlanValidationResult result = validator(parameterizedSource, compose)
                .validate(candidate, policy, limits);

        assertThat(result.valid()).isTrue();
        assertThat(result.issues()).isEmpty();
        assertThat(result.plan().scenarioId()).isEqualTo("test");
        assertThat(result.plan().estimatedCost()).isEqualTo(3);
        assertThat(result.plan().nodes()).extracting(ValidatedDynamicTaskNode::id)
                .containsExactly("source", "compose");
        assertThat(result.plan().nodes().getFirst().arguments()).containsEntry("limit", 3);
        assertThatThrownBy(() -> result.plan().nodes().add(result.plan().nodes().getFirst()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.plan().nodes().getFirst().arguments().put("limit", 4))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidPlans")
    void rejectsInvalidStructures(CandidateDynamicTaskPlan candidate, String code) {
        DynamicPlanValidationResult result = validator(source, compose).validate(candidate, policy, limits);

        assertThat(result.valid()).isFalse();
        assertThat(result.issues()).extracting(DynamicPlanIssue::code).contains(code);
    }

    static Stream<Arguments> invalidPlans() {
        return Stream.of(
                Arguments.of(new CandidateDynamicTaskPlan("2", List.of(
                                node("a", "source.read", List.of(), Map.of()))),
                        "UNSUPPORTED_SCHEMA_VERSION"),
                Arguments.of(new CandidateDynamicTaskPlan("1", List.of()), "PLAN_EMPTY"),
                Arguments.of(plan(node("A", "source.read", List.of(), Map.of())),
                        "INVALID_NODE_ID"),
                Arguments.of(plan(
                                node("a", "source.read", List.of(), Map.of()),
                                node("a", "result.compose", List.of("a"), Map.of())),
                        "DUPLICATE_NODE_ID"),
                Arguments.of(plan(node("a", "missing", List.of(), Map.of())),
                        "UNKNOWN_CAPABILITY"),
                Arguments.of(plan(node("a", "source.read", List.of("missing"), Map.of())),
                        "UNKNOWN_DEPENDENCY"),
                Arguments.of(plan(node("a", "source.read", List.of("a"), Map.of())),
                        "CYCLIC_DEPENDENCY"));
    }

    @Test
    void rejectsDuplicateDependenciesAsInvalidNodeArguments() {
        CandidateDynamicTaskPlan candidate = plan(
                node("source", "source.read", List.of(), Map.of()),
                node("compose", "result.compose", List.of("source", "source"), Map.of()));

        assertInvalid(candidate, policy, limits, "INVALID_ARGUMENTS", source, compose);
    }

    @Test
    void rejectsPlansOverTheNodeLimit() {
        CandidateDynamicTaskPlan candidate = plan(
                node("source", "source.read", List.of(), Map.of()),
                node("compose", "result.compose", List.of("source"), Map.of()));

        assertInvalid(candidate, policy, limitsWith(1, 4, 4, 4, 10),
                "PLAN_TOO_LARGE", source, compose);
    }

    @Test
    void rejectsPlansOverTheDepthLimit() {
        DynamicCapabilityDescriptor middle = descriptor(
                "middle.read", Set.of("source.read"), Map.of(), 1, true);
        DynamicCapabilityDescriptor finalNode = descriptor(
                "result.compose", Set.of("middle.read"), Map.of(), 1, true);
        CandidateDynamicTaskPlan candidate = plan(
                node("source", "source.read", List.of(), Map.of()),
                node("middle", "middle.read", List.of("source"), Map.of()),
                node("compose", "result.compose", List.of("middle"), Map.of()));

        assertInvalid(candidate, policyAllowing("source.read", "middle.read", "result.compose"),
                limitsWith(8, 2, 4, 4, 10), "MAX_DEPTH_EXCEEDED", source, middle, finalNode);
    }

    @Test
    void rejectsNodesOverTheDependencyLimit() {
        CandidateDynamicTaskPlan candidate = plan(
                node("left", "source.read", List.of(), Map.of()),
                node("right", "source.read", List.of(), Map.of()),
                node("compose", "result.compose", List.of("left", "right"), Map.of()));

        assertInvalid(candidate, policy, limitsWith(8, 4, 1, 4, 10),
                "MAX_DEPENDENCIES_EXCEEDED", source, compose);
    }

    @Test
    void rejectsNodesOverTheFanOutLimit() {
        DynamicCapabilityDescriptor middle = descriptor(
                "middle.read", Set.of("source.read"), Map.of(), 1, true);
        DynamicCapabilityDescriptor finalNode = descriptor(
                "result.compose", Set.of("middle.read"), Map.of(), 1, true);
        CandidateDynamicTaskPlan candidate = plan(
                node("source", "source.read", List.of(), Map.of()),
                node("left", "middle.read", List.of("source"), Map.of()),
                node("right", "middle.read", List.of("source"), Map.of()),
                node("compose", "result.compose", List.of("left", "right"), Map.of()));

        assertInvalid(candidate, policyAllowing("source.read", "middle.read", "result.compose"),
                limitsWith(8, 4, 4, 1, 10), "MAX_FAN_OUT_EXCEEDED", source, middle, finalNode);
    }

    @Test
    void rejectsDisconnectedOrMultipleTerminalGraphs() {
        CandidateDynamicTaskPlan candidate = plan(
                node("source", "source.read", List.of(), Map.of()),
                node("compose", "result.compose", List.of(), Map.of()));

        assertInvalid(candidate, policy, limits, "MISSING_FINAL_OUTPUT", source, compose);
    }

    @Test
    void truncatesIssuesAtTheConfiguredHardMaximum() {
        List<CandidateDynamicTaskNode> invalidNodes = IntStream.range(0, 25)
                .mapToObj(index -> node("INVALID_" + index, "missing", List.of(), Map.of()))
                .toList();
        DynamicPlanningLimits largePlanLimits = new DynamicPlanningLimits(30, 4, 4, 4, 100, 20);

        DynamicPlanValidationResult result = validator(source, compose)
                .validate(new CandidateDynamicTaskPlan("1", invalidNodes), policy, largePlanLimits);

        assertThat(result.valid()).isFalse();
        assertThat(result.issues()).hasSize(20);
    }

    @Test
    void rejectsCapabilitiesDisallowedByTheScenario() {
        DynamicPlanScenarioPolicy sourceOnly = policyAllowing("source.read");
        CandidateDynamicTaskPlan candidate = plan(
                node("source", "source.read", List.of(), Map.of()),
                node("compose", "result.compose", List.of("source"), Map.of()));

        assertInvalid(candidate, sourceOnly, limits, "SCENARIO_POLICY_VIOLATION", source, compose);
    }

    @Test
    void rejectsNonReadOnlyCapabilities() {
        DynamicCapabilityDescriptor write = new DynamicCapabilityDescriptor(
                "state.write", 1, DynamicEffectType.WRITE, Map.of(), Set.of(), 1, true);
        CandidateDynamicTaskPlan candidate = plan(
                node("write", "state.write", List.of(), Map.of()));

        assertInvalid(candidate, policyAllowing("state.write"), limits,
                "DISALLOWED_EFFECT", write);
    }

    @Test
    void rejectsIncompatibleDependencyCapabilities() {
        DynamicCapabilityDescriptor other = descriptor(
                "other.read", Set.of(), Map.of(), 1, true);
        CandidateDynamicTaskPlan candidate = plan(
                node("other", "other.read", List.of(), Map.of()),
                node("compose", "result.compose", List.of("other"), Map.of()));

        assertInvalid(candidate, policyAllowing("other.read", "result.compose"), limits,
                "INCOMPATIBLE_DEPENDENCY", other, compose);
    }

    @Test
    void rejectsPlansOverTheEstimatedCostBudget() {
        CandidateDynamicTaskPlan candidate = plan(
                node("source", "source.read", List.of(), Map.of()),
                node("compose", "result.compose", List.of("source"), Map.of()));

        assertInvalid(candidate, policy, limitsWith(8, 4, 4, 4, 2),
                "PLAN_BUDGET_EXCEEDED", source, compose);
    }

    @ParameterizedTest
    @MethodSource("invalidArgumentPlans")
    void rejectsArgumentsOutsideTheDescriptorContract(CandidateDynamicTaskPlan candidate) {
        DynamicCapabilityDescriptor parameterized = descriptor(
                "source.read",
                Set.of(),
                Map.of(
                        "query", new DynamicArgumentDefinition(
                                "query", DynamicArgumentType.STRING, true, null, 2L, 5L),
                        "limit", new DynamicArgumentDefinition(
                                "limit", DynamicArgumentType.INTEGER, false, 3, 1L, 5L),
                        "enabled", new DynamicArgumentDefinition(
                                "enabled", DynamicArgumentType.BOOLEAN, false, true, null, null)),
                1,
                true);

        assertInvalid(candidate, policy, limits, "INVALID_ARGUMENTS", parameterized, compose);
    }

    static Stream<CandidateDynamicTaskPlan> invalidArgumentPlans() {
        return Stream.of(
                plan(node("source", "source.read", List.of(), Map.of()),
                        node("compose", "result.compose", List.of("source"), Map.of())),
                plan(node("source", "source.read", List.of(), Map.of("query", "abc", "unknown", 1)),
                        node("compose", "result.compose", List.of("source"), Map.of())),
                plan(node("source", "source.read", List.of(), Map.of("query", "x")),
                        node("compose", "result.compose", List.of("source"), Map.of())),
                plan(node("source", "source.read", List.of(), Map.of("query", "abcdef")),
                        node("compose", "result.compose", List.of("source"), Map.of())),
                plan(node("source", "source.read", List.of(), Map.of("query", "abc", "limit", 6)),
                        node("compose", "result.compose", List.of("source"), Map.of())),
                plan(node("source", "source.read", List.of(), Map.of("query", "abc", "limit", 2L)),
                        node("compose", "result.compose", List.of("source"), Map.of())),
                plan(node("source", "source.read", List.of(), Map.of("query", "abc", "enabled", "true")),
                        node("compose", "result.compose", List.of("source"), Map.of())));
    }

    @Test
    void returnsScenarioPolicyIssuesAfterGenericNormalization() {
        DynamicPlanScenarioPolicy rejectingPolicy = new DynamicPlanScenarioPolicy() {
            @Override
            public String scenarioId() {
                return "test";
            }

            @Override
            public Set<String> allowedCapabilities() {
                return Set.of("source.read", "result.compose");
            }

            @Override
            public List<DynamicPlanIssue> validate(ValidatedDynamicTaskPlan plan) {
                assertThat(plan.nodes()).hasSize(2);
                return List.of(new DynamicPlanIssue("SCENARIO_POLICY_VIOLATION", "compose"));
            }
        };
        CandidateDynamicTaskPlan candidate = plan(
                node("source", "source.read", List.of(), Map.of()),
                node("compose", "result.compose", List.of("source"), Map.of()));

        assertInvalid(candidate, rejectingPolicy, limits,
                "SCENARIO_POLICY_VIOLATION", source, compose);
    }

    @Test
    void contractRecordsDefensivelyCopyCollectionsAndEnforceConstructionInvariants() {
        List<CandidateDynamicTaskNode> nodes = new ArrayList<>();
        Map<String, Object> arguments = new LinkedHashMap<>();
        List<String> dependencies = new ArrayList<>();
        CandidateDynamicTaskNode candidateNode = new CandidateDynamicTaskNode(
                "source", "source.read", dependencies, arguments);
        nodes.add(candidateNode);
        CandidateDynamicTaskPlan candidatePlan = new CandidateDynamicTaskPlan("1", nodes);
        dependencies.add("late");
        arguments.put("late", true);
        nodes.clear();

        assertThat(candidateNode.dependsOn()).isEmpty();
        assertThat(candidateNode.arguments()).isEmpty();
        assertThat(candidatePlan.nodes()).hasSize(1);
        assertThatThrownBy(() -> new DynamicPlanValidationResult(null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DynamicPlanValidationResult(
                new ValidatedDynamicTaskPlan("1", "test", List.of(), 0),
                List.of(new DynamicPlanIssue("INVALID_ARGUMENTS", "node"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void descriptorsAndLimitsRejectInvalidDefinitionsAndCopyTheirCollections() {
        Map<String, DynamicArgumentDefinition> arguments = new LinkedHashMap<>();
        arguments.put("limit", new DynamicArgumentDefinition(
                "limit", DynamicArgumentType.INTEGER, false, 3, 1L, 5L));
        Set<String> upstream = new LinkedHashSet<>(Set.of("source.read"));
        DynamicCapabilityDescriptor descriptor = descriptor(
                "result.compose", upstream, arguments, 1, true);
        arguments.clear();
        upstream.clear();

        assertThat(descriptor.arguments()).containsOnlyKeys("limit");
        assertThat(descriptor.allowedDependencyCapabilities()).containsExactly("source.read");
        assertThatThrownBy(() -> new DynamicCapabilityDescriptor(
                " ", 1, DynamicEffectType.READ_ONLY, Map.of(), Set.of(), 1, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DynamicCapabilityDescriptor(
                "valid", 0, DynamicEffectType.READ_ONLY, Map.of(), Set.of(), 1, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DynamicCapabilityDescriptor(
                "valid", 1, DynamicEffectType.READ_ONLY, Map.of(), Set.of(), 0, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DynamicPlanningLimits(0, 1, 1, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DynamicPlanningLimits(1, 1, 1, 1, 1, 21))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private DynamicTaskPlanValidator validator(DynamicCapabilityDescriptor... descriptors) {
        return new DynamicTaskPlanValidator(List.of(descriptors));
    }

    private void assertInvalid(
            CandidateDynamicTaskPlan candidate,
            DynamicPlanScenarioPolicy scenarioPolicy,
            DynamicPlanningLimits planningLimits,
            String expectedCode,
            DynamicCapabilityDescriptor... descriptors) {
        DynamicPlanValidationResult result = validator(descriptors)
                .validate(candidate, scenarioPolicy, planningLimits);

        assertThat(result.valid()).isFalse();
        assertThat(result.plan()).isNull();
        assertThat(result.issues()).extracting(DynamicPlanIssue::code).contains(expectedCode);
    }

    private static DynamicCapabilityDescriptor descriptor(
            String id,
            Set<String> allowedDependencies,
            Map<String, DynamicArgumentDefinition> arguments,
            int cost,
            boolean requireSuccessfulDependencies) {
        return new DynamicCapabilityDescriptor(
                id,
                1,
                DynamicEffectType.READ_ONLY,
                arguments,
                allowedDependencies,
                cost,
                requireSuccessfulDependencies);
    }

    private static CandidateDynamicTaskPlan plan(CandidateDynamicTaskNode... nodes) {
        return new CandidateDynamicTaskPlan("1", List.of(nodes));
    }

    private static CandidateDynamicTaskNode node(
            String id,
            String capability,
            List<String> dependencies,
            Map<String, Object> arguments) {
        return new CandidateDynamicTaskNode(id, capability, dependencies, arguments);
    }

    private static DynamicPlanScenarioPolicy policyAllowing(String... capabilities) {
        Set<String> allowed = Set.of(capabilities);
        return new DynamicPlanScenarioPolicy() {
            @Override
            public String scenarioId() {
                return "test";
            }

            @Override
            public Set<String> allowedCapabilities() {
                return allowed;
            }

            @Override
            public List<DynamicPlanIssue> validate(ValidatedDynamicTaskPlan plan) {
                return List.of();
            }
        };
    }

    private static DynamicPlanningLimits limitsWith(
            int maxNodes,
            int maxDepth,
            int maxDependencies,
            int maxFanOut,
            int maxCost) {
        return new DynamicPlanningLimits(
                maxNodes, maxDepth, maxDependencies, maxFanOut, maxCost, 20);
    }
}
