package io.github.imzmq.interview.routing.application.understanding;

import io.github.imzmq.interview.config.routing.RoutingUnderstandingProperties;
import io.github.imzmq.interview.routing.application.contract.UnderstandingService;
import io.github.imzmq.interview.routing.application.contract.result.DecisionSource;
import io.github.imzmq.interview.routing.application.contract.result.IntentRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class DefaultUnderstandingService implements UnderstandingService {

    private final IntentLlmResolver resolver;
    private final Executor executor;
    private final RoutingUnderstandingProperties properties;
    private final Clock clock;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong openUntilMillis = new AtomicLong();

    public DefaultUnderstandingService(
            IntentLlmResolver resolver,
            @Qualifier("routingUnderstandingExecutor") Executor executor,
            RoutingUnderstandingProperties properties) {
        this(resolver, executor, properties, Clock.systemUTC());
    }

    DefaultUnderstandingService(
            IntentLlmResolver resolver,
            Executor executor,
            RoutingUnderstandingProperties properties,
            Clock clock) {
        this.resolver = resolver;
        this.executor = executor;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public CompletionStage<IntentRecord> understand(String sessionId, String userId, String rawQuery, String history) {
        if (clock.millis() < openUntilMillis.get()) {
            return CompletableFuture.completedFuture(IntentRecord.fallback(sessionId, rawQuery, "circuit_open"));
        }
        try {
            CompletableFuture<IntentRecord> attempt = new CompletableFuture<>();
            FutureTask<Void> task = new FutureTask<>(() -> {
                try {
                    attempt.complete(resolver.resolve(sessionId, userId, rawQuery, history));
                } catch (Throwable error) {
                    attempt.completeExceptionally(error);
                }
                return null;
            });
            executor.execute(task);
            return attempt
                    .orTimeout(properties.getTimeoutMillis(), TimeUnit.MILLISECONDS)
                    .whenComplete((ignored, error) -> {
                        if (error instanceof TimeoutException) {
                            task.cancel(true);
                        }
                    })
                    .handle((record, error) -> finishAttempt(sessionId, rawQuery, record, error));
        } catch (RejectedExecutionException ex) {
            recordFailure();
            return CompletableFuture.completedFuture(IntentRecord.fallback(sessionId, rawQuery, "executor_rejected"));
        }
    }

    private IntentRecord finishAttempt(String sessionId, String rawQuery, IntentRecord record, Throwable error) {
        if (error != null) {
            recordFailure();
            return IntentRecord.fallback(sessionId, rawQuery, "understanding_failed:" + error.getClass().getSimpleName());
        }
        if (record == null || record.source() == DecisionSource.FALLBACK_RULE) {
            recordFailure();
            return record == null ? IntentRecord.fallback(sessionId, rawQuery, "empty_result") : record;
        }
        consecutiveFailures.set(0);
        openUntilMillis.set(0L);
        return record;
    }

    private void recordFailure() {
        if (consecutiveFailures.incrementAndGet() >= properties.getFailureThreshold()) {
            openUntilMillis.set(clock.millis() + properties.getOpenDurationMillis());
        }
    }
}
