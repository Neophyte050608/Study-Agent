package io.github.imzmq.interview.routing.application.understanding;

import io.github.imzmq.interview.config.routing.RoutingUnderstandingProperties;
import io.github.imzmq.interview.routing.application.contract.result.DecisionSource;
import io.github.imzmq.interview.routing.application.contract.result.IntentRecord;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultUnderstandingServiceTest {

    @Test
    void executesOnDedicatedExecutorAndReturnsSuccessfulRecord() throws Exception {
        AtomicReference<String> executorThread = new AtomicReference<>();
        Executor executor = task -> new Thread(() -> {
            executorThread.set(Thread.currentThread().getName());
            task.run();
        }, "RoutingUnderstanding-test").start();
        IntentRecord expected = IntentRecord.fallback("s", "q", "ok");
        IntentLlmResolver resolver = org.mockito.Mockito.mock(IntentLlmResolver.class);
        org.mockito.Mockito.when(resolver.resolve("s", "u", "q", "h")).thenReturn(expected);
        DefaultUnderstandingService service = new DefaultUnderstandingService(resolver, executor, properties(500, 3, 1000), Clock.systemUTC());

        assertThat(service.understand("s", "u", "q", "h").toCompletableFuture().get(1, TimeUnit.SECONDS)).isSameAs(expected);
        assertThat(executorThread.get()).startsWith("RoutingUnderstanding-");
    }

    @Test
    void timesOutAndOpensCircuitAfterConfiguredFailures() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        IntentLlmResolver resolver = org.mockito.Mockito.mock(IntentLlmResolver.class);
        org.mockito.Mockito.when(resolver.resolve(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(ignored -> {
                    calls.incrementAndGet();
                    Thread.sleep(200);
                    return IntentRecord.fallback("s", "q", "late");
                });
        DefaultUnderstandingService service = new DefaultUnderstandingService(resolver, runnable -> new Thread(runnable).start(),
                properties(20, 1, 5000), Clock.systemUTC());

        IntentRecord timedOut = service.understand("s", "u", "q", "h").toCompletableFuture().get(1, TimeUnit.SECONDS);
        IntentRecord openCircuit = service.understand("s", "u", "q2", "h").toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertThat(timedOut.source()).isEqualTo(DecisionSource.FALLBACK_RULE);
        assertThat(openCircuit.reason()).contains("circuit_open");
        assertThat(calls).hasValue(1);
    }

    @Test
    void interruptsUnderlyingResolverWhenAttemptTimesOut() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        IntentLlmResolver resolver = org.mockito.Mockito.mock(IntentLlmResolver.class);
        org.mockito.Mockito.when(resolver.resolve("s", "u", "q", "h"))
                .thenAnswer(ignored -> {
                    started.countDown();
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException ex) {
                        interrupted.countDown();
                        Thread.currentThread().interrupt();
                    }
                    return IntentRecord.fallback("s", "q", "late");
                });
        DefaultUnderstandingService service = new DefaultUnderstandingService(
                resolver,
                task -> new Thread(task, "RoutingUnderstanding-cancellable-test").start(),
                properties(500, 3, 1_000),
                Clock.systemUTC()
        );

        var resultFuture = service.understand("s", "u", "q", "h").toCompletableFuture();

        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        IntentRecord result = resultFuture.get(2, TimeUnit.SECONDS);
        assertThat(result.reason()).contains("understanding_failed");
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void retriesAfterCircuitOpenDurationAndDoesNotRunOnRejectedCaller() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        IntentLlmResolver resolver = org.mockito.Mockito.mock(IntentLlmResolver.class);
        org.mockito.Mockito.when(resolver.resolve(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(ignored -> {
                    calls.incrementAndGet();
                    return IntentRecord.fallback("s", "q", "resolver fallback");
                });
        MutableClock clock = new MutableClock(1000);
        DefaultUnderstandingService service = new DefaultUnderstandingService(resolver, Runnable::run,
                properties(500, 1, 100), clock);

        service.understand("s", "u", "q", "h").toCompletableFuture().get();
        service.understand("s", "u", "q", "h").toCompletableFuture().get();
        clock.advance(101);
        service.understand("s", "u", "q", "h").toCompletableFuture().get();
        assertThat(calls).hasValue(2);

        DefaultUnderstandingService rejected = new DefaultUnderstandingService(resolver,
                task -> { throw new RejectedExecutionException("full"); }, properties(500, 3, 100), clock);
        IntentRecord result = rejected.understand("s", "u", "rejected", "h").toCompletableFuture().get();
        assertThat(result.source()).isEqualTo(DecisionSource.FALLBACK_RULE);
        assertThat(result.reason()).contains("executor_rejected");
        assertThat(calls).hasValue(2);
    }

    private RoutingUnderstandingProperties properties(long timeout, int threshold, long openDuration) {
        RoutingUnderstandingProperties properties = new RoutingUnderstandingProperties();
        properties.setTimeoutMillis(timeout);
        properties.setFailureThreshold(threshold);
        properties.setOpenDurationMillis(openDuration);
        return properties;
    }

    private static final class MutableClock extends Clock {
        private long millis;
        private MutableClock(long millis) { this.millis = millis; }
        void advance(long delta) { millis += delta; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }
}
