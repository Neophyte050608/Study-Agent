package io.github.imzmq.interview.agent.infrastructure.planning;

import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicCapabilityDescriptor;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicExecutionContext;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanIssue;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanObservationOperations;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanScenarioPolicy;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanValidationResult;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanningLimits;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicPlanningShadowOperations;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.ValidatedDynamicTaskPlan;
import io.github.imzmq.interview.agent.application.execution.dynamic.DynamicPlanFingerprint;
import io.github.imzmq.interview.agent.application.execution.dynamic.DynamicTaskPlanValidator;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Component
public final class DynamicPlanningShadowRunner implements DynamicPlanningShadowOperations, AutoCloseable {
    private static final String PLANNER_TYPE = "llm-shadow";
    private static final String PLANNER_VERSION = "v1";

    private final LlmDynamicTaskPlanner planner;
    private final DynamicPlanObservationOperations observations;
    private final DynamicPlanFingerprint fingerprint;
    private final Duration timeout;
    private final ThreadPoolExecutor shadowWorkers;
    private final ScheduledExecutorService timeoutScheduler;

    @Autowired
    public DynamicPlanningShadowRunner(
            LlmDynamicTaskPlanner planner,
            DynamicPlanObservationOperations observations,
            DynamicPlanningShadowProperties properties) {
        this(planner, observations, new DynamicPlanFingerprint(),
                properties.getTimeout(), properties.getWorkers(), properties.getQueueCapacity());
    }

    public DynamicPlanningShadowRunner(
            LlmDynamicTaskPlanner planner,
            DynamicPlanObservationOperations observations,
            DynamicPlanFingerprint fingerprint,
            Duration timeout,
            int workerCount,
            int queueCapacity) {
        this.planner = Objects.requireNonNull(planner);
        this.observations = Objects.requireNonNull(observations);
        this.fingerprint = Objects.requireNonNull(fingerprint);
        this.timeout = Objects.requireNonNull(timeout);
        if (timeout.isZero() || timeout.isNegative() || workerCount <= 0 || queueCapacity <= 0) {
            throw new IllegalArgumentException("Invalid shadow planning limits");
        }
        this.shadowWorkers = new ThreadPoolExecutor(
                workerCount,
                workerCount,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                daemonFactory("dynamic-plan-shadow-"),
                new ThreadPoolExecutor.AbortPolicy());
        this.timeoutScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                daemonFactory("dynamic-plan-shadow-timeout-"));
    }

    @Override
    public void submit(
            DynamicExecutionContext context,
            ValidatedDynamicTaskPlan rulePlan,
            DynamicPlanScenarioPolicy policy,
            List<DynamicCapabilityDescriptor> descriptors,
            DynamicPlanningLimits limits) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(rulePlan);
        Objects.requireNonNull(policy);
        List<DynamicCapabilityDescriptor> descriptorSnapshot = List.copyOf(descriptors);
        Objects.requireNonNull(limits);

        long startedAt = System.nanoTime();
        AtomicBoolean terminalObservation = new AtomicBoolean();
        AtomicReference<Future<?>> workerReference = new AtomicReference<>();
        AtomicReference<ScheduledFuture<?>> timeoutReference = new AtomicReference<>();
        try {
            Future<?> worker = shadowWorkers.submit(() -> runShadow(
                    context, rulePlan, policy, descriptorSnapshot, limits,
                    startedAt, terminalObservation, timeoutReference));
            workerReference.set(worker);
            ScheduledFuture<?> scheduled = timeoutScheduler.schedule(() -> {
                Future<?> active = workerReference.get();
                if (active != null
                        && active.cancel(true)
                        && terminalObservation.compareAndSet(false, true)) {
                    observations.rejected(
                            PLANNER_TYPE, PLANNER_VERSION, "SHADOW_TIMEOUT", elapsedMillis(startedAt));
                }
            }, timeout.toNanos(), TimeUnit.NANOSECONDS);
            timeoutReference.set(scheduled);
            if (terminalObservation.get()) {
                scheduled.cancel(false);
            }
        } catch (RejectedExecutionException exception) {
            if (terminalObservation.compareAndSet(false, true)) {
                observations.rejected(
                        PLANNER_TYPE, PLANNER_VERSION, "SHADOW_OVERLOADED", elapsedMillis(startedAt));
            }
        }
    }

    private void runShadow(
            DynamicExecutionContext context,
            ValidatedDynamicTaskPlan rulePlan,
            DynamicPlanScenarioPolicy policy,
            List<DynamicCapabilityDescriptor> descriptors,
            DynamicPlanningLimits limits,
            long startedAt,
            AtomicBoolean terminalObservation,
            AtomicReference<ScheduledFuture<?>> timeoutReference) {
        try {
            DynamicPlanParseResult parsed = planner.plan(context, descriptors);
            if (terminalObservation.get()) {
                return;
            }
            if (!parsed.success()) {
                reject(parsed.errorCode(), startedAt, terminalObservation);
                return;
            }
            DynamicPlanValidationResult validation =
                    new DynamicTaskPlanValidator(descriptors).validate(parsed.plan(), policy, limits);
            if (terminalObservation.get()) {
                return;
            }
            if (!validation.valid()) {
                String errorCode = validation.issues().stream()
                        .findFirst()
                        .map(DynamicPlanIssue::code)
                        .orElse("PLANNING_FAILED");
                reject(errorCode, startedAt, terminalObservation);
                return;
            }
            ValidatedDynamicTaskPlan shadowPlan = validation.plan();
            String ruleDigest = fingerprint.digest(rulePlan);
            String shadowDigest = fingerprint.digest(shadowPlan);
            if (terminalObservation.compareAndSet(false, true)) {
                observations.shadowCompared(
                        PLANNER_TYPE,
                        PLANNER_VERSION,
                        ruleDigest,
                        shadowDigest,
                        ruleDigest.equals(shadowDigest),
                        shadowPlan.nodes().size() - rulePlan.nodes().size(),
                        elapsedMillis(startedAt));
            }
        } catch (RuntimeException ignored) {
            reject("PLANNING_FAILED", startedAt, terminalObservation);
        } finally {
            ScheduledFuture<?> scheduled = timeoutReference.get();
            if (scheduled != null) {
                scheduled.cancel(false);
            }
        }
    }

    private void reject(String errorCode, long startedAt, AtomicBoolean terminalObservation) {
        if (terminalObservation.compareAndSet(false, true)) {
            observations.rejected(
                    PLANNER_TYPE, PLANNER_VERSION, errorCode, elapsedMillis(startedAt));
        }
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    @Override
    @PreDestroy
    public void close() {
        timeoutScheduler.shutdownNow();
        shadowWorkers.shutdownNow();
    }
}
