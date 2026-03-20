package com.example.interview.agent.a2a;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryA2ABusTest {

    @Test
    void shouldPublishToReceiverAndWildcard() {
        InMemoryA2ABus bus = new InMemoryA2ABus(new A2AIdempotencyStore(300, 20000));
        AtomicInteger receiverCount = new AtomicInteger(0);
        AtomicInteger wildcardCount = new AtomicInteger(0);
        bus.subscribe("InterviewOrchestratorAgent", message -> receiverCount.incrementAndGet());
        bus.subscribe("*", message -> wildcardCount.incrementAndGet());

        bus.publish(new A2AMessage(
                "1.0",
                "m1",
                "m1",
                "TaskRouterAgent",
                "InterviewOrchestratorAgent",
                "",
                A2AIntent.DELEGATE_TASK,
                Map.of("taskType", "INTERVIEW_START"),
                Map.of(),
                A2AStatus.PENDING,
                null,
                new A2AMetadata("task-routing", "test", Map.of()),
                new A2ATrace("t1", ""),
                Instant.now()
        ));

        assertEquals(1, receiverCount.get());
        assertEquals(1, wildcardCount.get());
    }

    @Test
    void shouldDropDuplicateMessageId() {
        InMemoryA2ABus bus = new InMemoryA2ABus(new A2AIdempotencyStore(300, 20000));
        AtomicInteger receiverCount = new AtomicInteger(0);
        bus.subscribe("InterviewOrchestratorAgent", message -> receiverCount.incrementAndGet());
        A2AMessage message = new A2AMessage(
                "1.0",
                "dup-1",
                "corr-1",
                "TaskRouterAgent",
                "InterviewOrchestratorAgent",
                "",
                A2AIntent.DELEGATE_TASK,
                Map.of("taskType", "INTERVIEW_START"),
                Map.of(),
                A2AStatus.PENDING,
                null,
                new A2AMetadata("task-routing", "test", Map.of()),
                new A2ATrace("t1", ""),
                Instant.now()
        );
        bus.publish(message);
        bus.publish(message);
        assertEquals(1, receiverCount.get());
    }

    @Test
    void shouldSupportRequestReply() {
        InMemoryA2ABus bus = new InMemoryA2ABus(new A2AIdempotencyStore(300, 20000));
        bus.subscribe("WorkerAgent", request -> {
            A2AMessage reply = new A2AMessage(
                    "1.0",
                    "reply-1",
                    request.correlationId(),
                    "WorkerAgent",
                    "GatewayAgent",
                    "",
                    A2AIntent.RETURN_RESULT,
                    Map.of("ok", true),
                    Map.of(),
                    A2AStatus.DONE,
                    null,
                    new A2AMetadata("reply", "test", Map.of()),
                    new A2ATrace("t2", request.messageId()),
                    Instant.now()
            );
            bus.publish(reply);
        });

        Optional<A2AMessage> response = bus.requestReply(new A2AMessage(
                "1.0",
                "req-1",
                "corr-req-1",
                "GatewayAgent",
                "WorkerAgent",
                "GatewayAgent",
                A2AIntent.EXECUTE_TASK,
                Map.of("task", "demo"),
                Map.of(),
                A2AStatus.PENDING,
                null,
                new A2AMetadata("request", "test", Map.of()),
                new A2ATrace("t2", ""),
                Instant.now()
        ));

        assertTrue(response.isPresent());
        assertEquals("corr-req-1", response.get().correlationId());
        assertEquals(A2AIntent.RETURN_RESULT, response.get().intent());
    }

    @Test
    void shouldRetryRequestReplyWithBackoff() {
        InMemoryA2ABus bus = new InMemoryA2ABus(new A2AIdempotencyStore(300, 20000));
        AtomicInteger received = new AtomicInteger(0);
        bus.subscribe("WorkerAgent", request -> {
            int count = received.incrementAndGet();
            if (count >= 2) {
                bus.publish(new A2AMessage(
                        "1.0",
                        "reply-2",
                        request.correlationId(),
                        "WorkerAgent",
                        "GatewayAgent",
                        "",
                        A2AIntent.RETURN_RESULT,
                        Map.of("ok", true),
                        Map.of(),
                        A2AStatus.DONE,
                        null,
                        new A2AMetadata("reply", "test", Map.of()),
                        new A2ATrace("t3", request.messageId()),
                        Instant.now()
                ));
            }
        });
        Optional<A2AMessage> response = bus.requestReply(new A2AMessage(
                "1.0",
                "req-retry-1",
                "corr-retry-1",
                "GatewayAgent",
                "WorkerAgent",
                "GatewayAgent",
                A2AIntent.EXECUTE_TASK,
                Map.of("task", "retry"),
                Map.of("replyTimeoutMs", 50, "replyRetries", 2, "replyBackoffMs", 30),
                A2AStatus.PENDING,
                null,
                new A2AMetadata("request", "test", Map.of()),
                new A2ATrace("t3", ""),
                Instant.now()
        ));

        assertTrue(response.isPresent());
        assertEquals(2, received.get());
    }
}
