package com.example.interview.agent.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RocketMqA2ABusTest {

    @Test
    void shouldSendByTemplate() {
        InMemoryA2ABus fallback = new InMemoryA2ABus(new A2AIdempotencyStore(300, 20000));
        RocketMQTemplate template = mock(RocketMQTemplate.class);
        RocketMqA2ABus bus = new RocketMqA2ABus(fallback, template, new ObjectMapper().findAndRegisterModules(), "a2a-events", 2, 0);

        bus.publish(sampleMessage());

        verify(template, times(1)).convertAndSend(anyString(), anyString());
    }

    @Test
    void shouldFallbackWhenTemplateSendFailed() {
        InMemoryA2ABus fallback = new InMemoryA2ABus(new A2AIdempotencyStore(300, 20000));
        AtomicInteger received = new AtomicInteger(0);
        fallback.subscribe("InterviewOrchestratorAgent", message -> received.incrementAndGet());
        RocketMQTemplate template = mock(RocketMQTemplate.class);
        doThrow(new RuntimeException("send failed")).when(template).convertAndSend(anyString(), anyString());
        RocketMqA2ABus bus = new RocketMqA2ABus(fallback, template, new ObjectMapper().findAndRegisterModules(), "a2a-events", 2, 0);

        bus.publish(sampleMessage());

        assertEquals(1, received.get());
        verify(template, times(3)).convertAndSend(anyString(), anyString());
    }

    @Test
    void shouldDispatchInboundToFallbackSubscribers() throws Exception {
        InMemoryA2ABus fallback = new InMemoryA2ABus(new A2AIdempotencyStore(300, 20000));
        AtomicInteger received = new AtomicInteger(0);
        fallback.subscribe("InterviewOrchestratorAgent", message -> received.incrementAndGet());
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        RocketMqA2ABus bus = new RocketMqA2ABus(fallback, null, mapper, "a2a-events", 2, 0);
        String raw = mapper.writeValueAsString(sampleMessage());

        boolean ok = bus.dispatchInbound(raw);

        assertEquals(true, ok);
        assertEquals(1, received.get());
    }

    @Test
    void shouldReturnFalseWhenInboundInvalid() {
        InMemoryA2ABus fallback = new InMemoryA2ABus(new A2AIdempotencyStore(300, 20000));
        RocketMqA2ABus bus = new RocketMqA2ABus(fallback, null, new ObjectMapper().findAndRegisterModules(), "a2a-events", 2, 0);
        assertFalse(bus.dispatchInbound("{invalid-json"));
    }

    private A2AMessage sampleMessage() {
        return new A2AMessage(
                "1.0",
                "m1",
                "c1",
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
    }
}
