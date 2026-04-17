package io.github.imzmq.interview.agent.a2a;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryA2ABusTest {

    @Test
    void publishShouldIsolateDirectHandlerExceptionAndContinueDispatch() {
        InMemoryA2ABus bus = new InMemoryA2ABus(new A2AIdempotencyStore(300, 20000));
        AtomicInteger directExecuted = new AtomicInteger();
        AtomicInteger wildcardExecuted = new AtomicInteger();

        bus.subscribe("RollingSummaryAgent", message -> {
            throw new RuntimeException("boom-direct");
        });
        bus.subscribe("RollingSummaryAgent", message -> directExecuted.incrementAndGet());
        bus.subscribe("*", message -> wildcardExecuted.incrementAndGet());

        A2AMessage message = messageFor("RollingSummaryAgent");
        assertDoesNotThrow(() -> bus.publish(message));

        assertEquals(1, directExecuted.get());
        assertEquals(1, wildcardExecuted.get());
    }

    @Test
    void publishShouldIsolateWildcardHandlerExceptionAndContinueDispatch() {
        InMemoryA2ABus bus = new InMemoryA2ABus(new A2AIdempotencyStore(300, 20000));
        AtomicInteger wildcardExecuted = new AtomicInteger();

        bus.subscribe("RollingSummaryAgent", message -> { });
        bus.subscribe("*", message -> {
            throw new RuntimeException("boom-wildcard");
        });
        bus.subscribe("*", message -> wildcardExecuted.incrementAndGet());

        A2AMessage message = messageFor("RollingSummaryAgent");
        assertDoesNotThrow(() -> bus.publish(message));

        assertEquals(1, wildcardExecuted.get());
    }

    private static A2AMessage messageFor(String receiver) {
        return new A2AMessage(
                "1.0",
                UUID.randomUUID().toString(),
                "corr-" + UUID.randomUUID(),
                "TaskRouterAgent",
                receiver,
                null,
                A2AIntent.ROLLING_SUMMARY,
                Map.of("text", "hello"),
                Map.of(),
                A2AStatus.PENDING,
                null,
                null,
                null,
                Instant.now()
        );
    }
}

