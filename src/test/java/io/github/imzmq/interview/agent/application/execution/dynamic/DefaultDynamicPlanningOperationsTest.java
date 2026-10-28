package io.github.imzmq.interview.agent.application.execution.dynamic;

import io.github.imzmq.interview.agent.application.contract.execution.DagNode;
import io.github.imzmq.interview.agent.application.contract.execution.DagNodeResult;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.CandidateDynamicTaskNode;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.CandidateDynamicTaskPlan;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicCapabilityDescriptor;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicEffectType;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicExecutionContext;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanIssue;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanScenarioPolicy;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanningLimits;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskNode;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskPlan;
import io.github.imzmq.interview.agent.application.contract.extension.DynamicNodeCapability;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultDynamicPlanningOperationsTest {
    @Test
    void exposesAnImmutableDescriptorSnapshotAndOwnsValidationAndCompilation() {
        DynamicNodeCapability<NoArguments> capability = new TestCapability();
        DefaultDynamicPlanningOperations operations =
                new DefaultDynamicPlanningOperations(List.of(capability));
        CandidateDynamicTaskPlan candidate = new CandidateDynamicTaskPlan("1", List.of(
                new CandidateDynamicTaskNode("source", "source.read", List.of(), Map.of())));
        DynamicPlanScenarioPolicy policy = new DynamicPlanScenarioPolicy() {
            @Override public String scenarioId() { return "test"; }
            @Override public Set<String> allowedCapabilities() { return Set.of("source.read"); }
            @Override public List<DynamicPlanIssue> validate(ValidatedDynamicTaskPlan plan) {
                return List.of();
            }
        };

        var result = operations.validate(candidate, policy,
                new DynamicPlanningLimits(2, 1, 1, 1, 1, 2));
        List<DagNode> nodes = operations.compile(result.plan(),
                new DynamicExecutionContext("owner", "topic", "request"));

        assertThat(result.valid()).isTrue();
        assertThat(operations.digest(result.plan())).hasSize(64);
        assertThat(nodes).extracting(DagNode::id).containsExactly("source");
        assertThat(nodes.getFirst().action().apply(Map.of())).isEqualTo("owner:topic");
        assertThat(operations.descriptors()).containsExactly(capability.descriptor());
        assertThatThrownBy(() -> operations.descriptors().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void plansWithLatestVersionAndCompilesPersistedPlanWithExactVersion() {
        DynamicNodeCapability<NoArguments> versionOne = new VersionedTestCapability(1);
        DynamicNodeCapability<NoArguments> versionTwo = new VersionedTestCapability(2);
        DefaultDynamicPlanningOperations operations =
                new DefaultDynamicPlanningOperations(List.of(versionOne, versionTwo));
        CandidateDynamicTaskPlan candidate = new CandidateDynamicTaskPlan("1", List.of(
                new CandidateDynamicTaskNode("source", "source.read", List.of(), Map.of())));

        var latest = operations.validate(candidate, policy(),
                new DynamicPlanningLimits(2, 1, 1, 1, 1, 2));
        var persisted = new ValidatedDynamicTaskPlan("1", "test", List.of(
                new ValidatedDynamicTaskNode(
                        "source", "source.read", 1, List.of(), Map.of())), 1);
        DynamicExecutionContext context = new DynamicExecutionContext("owner", "topic", "request");

        assertThat(latest.valid()).isTrue();
        assertThat(latest.plan().nodes()).extracting(node -> node.capabilityVersion())
                .containsExactly(2);
        assertThat(operations.compile(latest.plan(), context).getFirst().action().apply(Map.of()))
                .isEqualTo("v2:owner:topic");
        assertThat(operations.compile(persisted, context).getFirst().action().apply(Map.of()))
                .isEqualTo("v1:owner:topic");
    }

    private static DynamicPlanScenarioPolicy policy() {
        return new DynamicPlanScenarioPolicy() {
            @Override public String scenarioId() { return "test"; }
            @Override public Set<String> allowedCapabilities() { return Set.of("source.read"); }
            @Override public List<DynamicPlanIssue> validate(ValidatedDynamicTaskPlan plan) {
                return List.of();
            }
        };
    }

    private record NoArguments() {
    }

    private static final class TestCapability implements DynamicNodeCapability<NoArguments> {
        private static final DynamicCapabilityDescriptor DESCRIPTOR = new DynamicCapabilityDescriptor(
                "source.read", 1, DynamicEffectType.READ_ONLY,
                Map.of(), Set.of(), 1, true);

        @Override public DynamicCapabilityDescriptor descriptor() { return DESCRIPTOR; }
        @Override public NoArguments decodeArguments(Map<String, Object> arguments) {
            return new NoArguments();
        }
        @Override public Object execute(
                DynamicExecutionContext context,
                Map<String, DagNodeResult> upstream,
                NoArguments arguments) {
            return context.owner() + ":" + context.topic();
        }
    }

    private static final class VersionedTestCapability implements DynamicNodeCapability<NoArguments> {
        private final DynamicCapabilityDescriptor descriptor;

        private VersionedTestCapability(int version) {
            this.descriptor = new DynamicCapabilityDescriptor(
                    "source.read", version, DynamicEffectType.READ_ONLY,
                    Map.of(), Set.of(), 1, true);
        }

        @Override public DynamicCapabilityDescriptor descriptor() { return descriptor; }
        @Override public NoArguments decodeArguments(Map<String, Object> arguments) {
            return new NoArguments();
        }
        @Override public Object execute(
                DynamicExecutionContext context,
                Map<String, DagNodeResult> upstream,
                NoArguments arguments) {
            return "v" + descriptor.capabilityVersion() + ":" + context.owner() + ":" + context.topic();
        }
    }
}
