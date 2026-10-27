package io.github.imzmq.interview.agent.infrastructure.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicCapabilityDescriptor;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicExecutionContext;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanIssue;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanObservationOperations;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanScenarioPolicy;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanningLimits;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskNode;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskPlan;
import io.github.imzmq.interview.agent.application.execution.dynamic.DynamicPlanFingerprint;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicPlanningShadowRunnerTest {
    private static final DynamicExecutionContext CONTEXT =
            new DynamicExecutionContext("private-owner", "Java", "参考资料");
    private static final DynamicPlanningLimits LIMITS =
            new DynamicPlanningLimits(8, 4, 4, 4, 10, 20);
    private static final String VALID_PLAN = """
            {"schemaVersion":"1","nodes":[
              {"id":"evidence","capability":"knowledge.evidence.retrieve","dependsOn":[],"arguments":{"limit":4}},
              {"id":"compose","capability":"learning.plan.compose","dependsOn":["evidence"],"arguments":{}}
            ]}
            """;

    @Test
    void submitReturnsWhileTheDedicatedWorkerIsStillBlocked() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> workerName = new AtomicReference<>();
        RecordingObserver observer = new RecordingObserver();
        try (DynamicPlanningShadowRunner runner = runner((system, user) -> {
            workerName.set(Thread.currentThread().getName());
            entered.countDown();
            await(release);
            return VALID_PLAN;
        }, observer, Duration.ofSeconds(2), 1, 1)) {
            runner.submit(CONTEXT, rulePlan(), policy(), descriptors(), LIMITS);

            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(workerName.get()).startsWith("dynamic-plan-shadow-");
            assertThat(observer.terminal).isEmpty();
            release.countDown();
            assertThat(observer.compared.await(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void validCandidateIsValidatedFingerprintedAndComparedWithoutCompilation() throws Exception {
        RecordingObserver observer = new RecordingObserver();
        try (DynamicPlanningShadowRunner runner = runner(
                (system, user) -> VALID_PLAN, observer, Duration.ofSeconds(1), 1, 1)) {
            runner.submit(CONTEXT, rulePlan(), policy(), descriptors(), LIMITS);

            assertThat(observer.compared.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(observer.ruleDigest).matches("[0-9a-f]{64}");
            assertThat(observer.shadowDigest).isEqualTo(observer.ruleDigest);
            assertThat(observer.same).isTrue();
            assertThat(observer.nodeDelta).isZero();
            assertThat(observer.compiledCalls).isZero();
        }
    }

    @Test
    void invalidOutputAndModelFailureAreContainedAsStableRejections() throws Exception {
        RecordingObserver invalidObserver = new RecordingObserver();
        try (DynamicPlanningShadowRunner runner = runner(
                (system, user) -> "explanation " + VALID_PLAN,
                invalidObserver, Duration.ofSeconds(1), 1, 1)) {
            runner.submit(CONTEXT, rulePlan(), policy(), descriptors(), LIMITS);
            assertThat(invalidObserver.rejected.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(invalidObserver.terminal).containsExactly("INVALID_PLAN_JSON");
        }

        RecordingObserver failureObserver = new RecordingObserver();
        try (DynamicPlanningShadowRunner runner = runner((system, user) -> {
            throw new IllegalStateException("private token");
        }, failureObserver, Duration.ofSeconds(1), 1, 1)) {
            runner.submit(CONTEXT, rulePlan(), policy(), descriptors(), LIMITS);
            assertThat(failureObserver.rejected.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(failureObserver.terminal).containsExactly("PLANNER_UNAVAILABLE");
        }
    }

    @Test
    void timeoutInterruptsCooperativeModelAndPublishesOneTerminalResult() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        RecordingObserver observer = new RecordingObserver();
        try (DynamicPlanningShadowRunner runner = runner((system, user) -> {
            try {
                Thread.sleep(10_000);
                return VALID_PLAN;
            } catch (InterruptedException exception) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted");
            }
        }, observer, Duration.ofMillis(80), 1, 1)) {
            runner.submit(CONTEXT, rulePlan(), policy(), descriptors(), LIMITS);

            assertThat(observer.rejected.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(30);
            assertThat(observer.terminal).containsExactly("SHADOW_TIMEOUT");
        }
    }

    @Test
    void interruptedButUncooperativeWorkerKeepsPhysicalCapacityBounded() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean keepRunning = new AtomicBoolean(true);
        RecordingObserver observer = new RecordingObserver();
        try (DynamicPlanningShadowRunner runner = runner((system, user) -> {
            entered.countDown();
            while (keepRunning.get()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ignored) {
                    // Deliberately ignore cancellation to prove no replacement capacity is created.
                }
            }
            release.countDown();
            return VALID_PLAN;
        }, observer, Duration.ofMillis(80), 1, 1)) {
            runner.submit(CONTEXT, rulePlan(), policy(), descriptors(), LIMITS);
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            runner.submit(CONTEXT, rulePlan(), policy(), descriptors(), LIMITS);
            runner.submit(CONTEXT, rulePlan(), policy(), descriptors(), LIMITS);

            assertThat(observer.awaitCode("SHADOW_OVERLOADED", 1, TimeUnit.SECONDS)).isTrue();
            keepRunning.set(false);
            assertThat(release.await(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void closeInterruptsWorkerAndStopsPendingTimeoutObservation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        RecordingObserver observer = new RecordingObserver();
        DynamicPlanningShadowRunner runner = runner((system, user) -> {
            entered.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException exception) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return VALID_PLAN;
        }, observer, Duration.ofMillis(150), 1, 1);
        runner.submit(CONTEXT, rulePlan(), policy(), descriptors(), LIMITS);
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

        runner.close();

        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(220);
        assertThat(observer.terminal).doesNotContain("SHADOW_TIMEOUT");
    }

    private static DynamicPlanningShadowRunner runner(
            io.github.imzmq.interview.modelrouting.application.contract.DynamicPlanningModelOperations model,
            RecordingObserver observer,
            Duration timeout,
            int workers,
            int queueCapacity) {
        LlmDynamicTaskPlanner planner = new LlmDynamicTaskPlanner(model,
                new StrictDynamicPlanJsonParser(new ObjectMapper(), 32 * 1024, 4 * 1024));
        return new DynamicPlanningShadowRunner(
                planner, observer, new DynamicPlanFingerprint(), timeout, workers, queueCapacity);
    }

    private static List<DynamicCapabilityDescriptor> descriptors() {
        return LlmDynamicTaskPlannerTest.descriptors();
    }

    private static ValidatedDynamicTaskPlan rulePlan() {
        return new ValidatedDynamicTaskPlan("1", "learning-plan-preview", List.of(
                new ValidatedDynamicTaskNode("evidence", "knowledge.evidence.retrieve", 1,
                        List.of(), Map.of("limit", 4)),
                new ValidatedDynamicTaskNode("compose", "learning.plan.compose", 1,
                        List.of("evidence"), Map.of())), 4);
    }

    private static DynamicPlanScenarioPolicy policy() {
        return new DynamicPlanScenarioPolicy() {
            @Override
            public String scenarioId() {
                return "learning-plan-preview";
            }

            @Override
            public Set<String> allowedCapabilities() {
                return Set.of("knowledge.evidence.retrieve", "learning.plan.compose");
            }

            @Override
            public List<DynamicPlanIssue> validate(ValidatedDynamicTaskPlan plan) {
                return List.of();
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        }
    }

    private static final class RecordingObserver implements DynamicPlanObservationOperations {
        private final List<String> terminal = new CopyOnWriteArrayList<>();
        private final CountDownLatch rejected = new CountDownLatch(1);
        private final CountDownLatch compared = new CountDownLatch(1);
        private volatile String ruleDigest;
        private volatile String shadowDigest;
        private volatile boolean same;
        private volatile int nodeDelta;
        private volatile int compiledCalls;

        @Override public void requested(String plannerType, String plannerVersion) { }
        @Override public void generated(String plannerType, String plannerVersion,
                                        ValidatedDynamicTaskPlan plan, String digest, long durationMs) { }
        @Override public void rejected(String plannerType, String plannerVersion,
                                       String errorCode, long durationMs) {
            terminal.add(errorCode);
            rejected.countDown();
        }
        @Override public void compiled(String plannerType, String plannerVersion,
                                       ValidatedDynamicTaskPlan plan, String digest, long durationMs) {
            compiledCalls++;
        }
        @Override public void fallback(String plannerType, String plannerVersion, String errorCode) { }
        @Override public void shadowCompared(String ruleDigest, String shadowDigest,
                                             boolean same, int nodeDelta, long durationMs) {
            this.ruleDigest = ruleDigest;
            this.shadowDigest = shadowDigest;
            this.same = same;
            this.nodeDelta = nodeDelta;
            terminal.add("COMPARED");
            compared.countDown();
        }

        boolean awaitCode(String code, long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (System.nanoTime() < deadline) {
                if (terminal.contains(code)) {
                    return true;
                }
                Thread.sleep(5);
            }
            return terminal.contains(code);
        }
    }
}
