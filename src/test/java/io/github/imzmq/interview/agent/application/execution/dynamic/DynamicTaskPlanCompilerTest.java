package io.github.imzmq.interview.agent.application.execution.dynamic;

import io.github.imzmq.interview.agent.application.contract.execution.DagExecutionResult;
import io.github.imzmq.interview.agent.application.contract.execution.DagNode;
import io.github.imzmq.interview.agent.application.contract.execution.DagNodeResult;
import io.github.imzmq.interview.agent.application.contract.execution.DagNodeStatus;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicCapabilityDescriptor;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicEffectType;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicExecutionContext;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskNode;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskPlan;
import io.github.imzmq.interview.agent.application.contract.extension.DynamicNodeCapability;
import io.github.imzmq.interview.agent.application.execution.FixedDagExecutor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicTaskPlanCompilerTest {
    private static final DynamicExecutionContext CONTEXT =
            new DynamicExecutionContext("owner-secret", "graphs", "request-secret");

    @Test
    void decodesEveryNodeBeforeReturningExecutableDagNodes() {
        AtomicInteger decoded = new AtomicInteger();
        AtomicInteger executed = new AtomicInteger();
        TestCapability source = capability("source.read", Set.of(), decoded, executed,
                (upstream, arguments) -> CONTEXT.topic() + ":" + arguments.limit());
        ValidatedDynamicTaskPlan plan = plan(
                node("source", "source.read", List.of(), Map.of("limit", 4)));

        List<DagNode> compiled = compiler(source).compile(plan, CONTEXT);

        assertThat(decoded).hasValue(1);
        assertThat(executed).hasValue(0);
        assertThat(compiled).extracting(DagNode::id).containsExactly("source");
        assertThat(compiled.getFirst().dependencies()).isEmpty();
    }

    @Test
    void compilationResolvesAllNodesBeforeAnyExecutionAndSanitizesDecodeFailures() {
        AtomicInteger executions = new AtomicInteger();
        TestCapability valid = capability("source.read", Set.of(), new AtomicInteger(), executions,
                (upstream, arguments) -> "unused");
        TestCapability invalid = new TestCapability(
                descriptor("result.compose", Set.of("source.read")), new AtomicInteger(), executions,
                (upstream, arguments) -> "unused") {
            @Override
            public LimitArguments decodeArguments(Map<String, Object> normalized) {
                throw new IllegalStateException("private decoder details");
            }
        };
        ValidatedDynamicTaskPlan plan = plan(
                node("source", "source.read", List.of(), Map.of()),
                node("compose", "result.compose", List.of("source"), Map.of()));

        assertThatThrownBy(() -> compiler(valid, invalid).compile(plan, CONTEXT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("PLAN_COMPILE_FAILED")
                .hasMessageNotContaining("private decoder details");
        assertThat(executions).hasValue(0);
    }

    @Test
    void compilesValidatedIdsDependenciesAndInvokesOnlySelectedCapabilities() {
        AtomicInteger sourceCalls = new AtomicInteger();
        AtomicInteger composeCalls = new AtomicInteger();
        AtomicInteger unselectedCalls = new AtomicInteger();
        TestCapability source = capability("source.read", Set.of(), new AtomicInteger(), sourceCalls,
                (upstream, arguments) -> CONTEXT.topic() + ":" + arguments.limit());
        TestCapability compose = capability("result.compose", Set.of("source.read"),
                new AtomicInteger(), composeCalls,
                (upstream, arguments) -> upstream.get("source").output() + ":composed");
        TestCapability unselected = capability("unused.read", Set.of(),
                new AtomicInteger(), unselectedCalls, (upstream, arguments) -> "unused");
        ValidatedDynamicTaskPlan plan = plan(
                node("source", "source.read", List.of(), Map.of("limit", 3)),
                node("compose", "result.compose", List.of("source"), Map.of()));

        List<DagNode> compiled = compiler(source, compose, unselected).compile(plan, CONTEXT);
        assertThat(compiled).extracting(DagNode::id).containsExactly("source", "compose");
        assertThat(compiled.get(1).dependencies()).containsExactly("source");

        try (FixedDagExecutor executor = new FixedDagExecutor()) {
            DagExecutionResult result = executor.execute(compiled, Duration.ofSeconds(3));
            assertThat(result.code()).isEqualTo("COMPLETED");
            assertThat(result.nodes().get("compose").output()).isEqualTo("graphs:3:composed");
        }
        assertThat(sourceCalls).hasValue(1);
        assertThat(composeCalls).hasValue(1);
        assertThat(unselectedCalls).hasValue(0);
    }

    @Test
    void letsIndependentSourcesRunBeforeCompose() throws Exception {
        CountDownLatch sourcesStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        TestCapability left = capability("left.read", Set.of(), new AtomicInteger(), new AtomicInteger(),
                (upstream, arguments) -> awaitSource(sourcesStarted, release, "left"));
        TestCapability right = capability("right.read", Set.of(), new AtomicInteger(), new AtomicInteger(),
                (upstream, arguments) -> awaitSource(sourcesStarted, release, "right"));
        TestCapability compose = capability("result.compose", Set.of("left.read", "right.read"),
                new AtomicInteger(), new AtomicInteger(),
                (upstream, arguments) -> upstream.get("left").output() + ":" + upstream.get("right").output());
        List<DagNode> compiled = compiler(left, right, compose).compile(plan(
                node("left", "left.read", List.of(), Map.of()),
                node("right", "right.read", List.of(), Map.of()),
                node("compose", "result.compose", List.of("left", "right"), Map.of())), CONTEXT);

        var caller = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (FixedDagExecutor executor = new FixedDagExecutor()) {
            var running = caller.submit(() -> executor.execute(compiled, Duration.ofSeconds(3)));
            assertThat(sourcesStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(running.isDone()).isFalse();
            release.countDown();
            assertThat(running.get(3, TimeUnit.SECONDS).nodes().get("compose").output())
                    .isEqualTo("left:right");
        } finally {
            release.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    void capabilityFailuresUseTheExistingNodeFailedResult() {
        TestCapability broken = capability("source.read", Set.of(), new AtomicInteger(), new AtomicInteger(),
                (upstream, arguments) -> { throw new IllegalStateException("private failure"); });
        List<DagNode> compiled = compiler(broken).compile(plan(
                node("source", "source.read", List.of(), Map.of())), CONTEXT);

        try (FixedDagExecutor executor = new FixedDagExecutor()) {
            DagExecutionResult result = executor.execute(compiled, Duration.ofSeconds(3));
            assertThat(result.nodes().get("source").status()).isEqualTo(DagNodeStatus.FAILED);
            assertThat(result.nodes().get("source").code()).isEqualTo("NODE_FAILED");
            assertThat(result.nodes().get("source").output()).isNull();
        }
    }

    private static DynamicTaskPlanCompiler compiler(DynamicNodeCapability<?>... capabilities) {
        return new DynamicTaskPlanCompiler(new DynamicCapabilityRegistry(List.of(capabilities)));
    }

    private static TestCapability capability(
            String id,
            Set<String> allowedDependencies,
            AtomicInteger decoded,
            AtomicInteger executed,
            BiFunction<Map<String, DagNodeResult>, LimitArguments, Object> action) {
        return new TestCapability(descriptor(id, allowedDependencies), decoded, executed, action);
    }

    private static DynamicCapabilityDescriptor descriptor(String id, Set<String> allowedDependencies) {
        return new DynamicCapabilityDescriptor(
                id, 1, DynamicEffectType.READ_ONLY, Map.of(), allowedDependencies, 1, true);
    }

    private static ValidatedDynamicTaskPlan plan(ValidatedDynamicTaskNode... nodes) {
        return new ValidatedDynamicTaskPlan("1", "test", List.of(nodes), nodes.length);
    }

    private static ValidatedDynamicTaskNode node(
            String id, String capability, List<String> dependencies, Map<String, Object> arguments) {
        return new ValidatedDynamicTaskNode(id, capability, 1, dependencies, arguments);
    }

    private static String awaitSource(CountDownLatch started, CountDownLatch release, String result) {
        started.countDown();
        try {
            if (!release.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("barrier expired");
            }
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private record LimitArguments(int limit) {
    }

    private static class TestCapability implements DynamicNodeCapability<LimitArguments> {
        private final DynamicCapabilityDescriptor descriptor;
        private final AtomicInteger decoded;
        private final AtomicInteger executed;
        private final BiFunction<Map<String, DagNodeResult>, LimitArguments, Object> action;

        TestCapability(
                DynamicCapabilityDescriptor descriptor,
                AtomicInteger decoded,
                AtomicInteger executed,
                BiFunction<Map<String, DagNodeResult>, LimitArguments, Object> action) {
            this.descriptor = descriptor;
            this.decoded = decoded;
            this.executed = executed;
            this.action = action;
        }

        @Override
        public DynamicCapabilityDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public LimitArguments decodeArguments(Map<String, Object> normalized) {
            decoded.incrementAndGet();
            return new LimitArguments((Integer) normalized.getOrDefault("limit", 1));
        }

        @Override
        public Object execute(
                DynamicExecutionContext context,
                Map<String, DagNodeResult> upstream,
                LimitArguments arguments) {
            executed.incrementAndGet();
            return action.apply(upstream, arguments);
        }
    }
}
