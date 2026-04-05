package com.example.interview.modelrouting;

import com.example.interview.service.DynamicModelFactory;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoutingChatServiceTest {

    @Test
    void shouldIncreaseFallbackAndFailureCountersWhenFirstPacketFails() {
        ModelRoutingProperties properties = new ModelRoutingProperties();
        properties.setEnabled(true);
        ModelRoutingProperties.Candidate candidate = new ModelRoutingProperties.Candidate();
        candidate.setName("m1");
        candidate.setProvider("openai");
        candidate.setEnabled(true);
        candidate.setPriority(1);
        properties.setCandidates(java.util.List.of(candidate));

        ModelSelector selector = new ModelSelector(properties);
        ModelHealthStore healthStore = new ModelHealthStore(properties);
        ModelRoutingExecutor executor = new ModelRoutingExecutor(healthStore);
        ModelProbeAwaiter awaiter = new ModelProbeAwaiter(properties, Runnable::run);
        DynamicModelFactory factory = mock(DynamicModelFactory.class);
        when(factory.getByRoutingCandidate("openai", "")).thenReturn(null);
        ChatModel fallbackModel = mock(ChatModel.class);
        Executor asyncExecutor = Runnable::run;
        RoutingChatService service = new RoutingChatService(asyncExecutor, properties, selector, executor, healthStore, factory, awaiter, fallbackModel);

        String result = service.callWithFirstPacketProbeSupplier(
                () -> "fallback-ok",
                "prompt",
                ModelRouteType.GENERAL,
                "first-packet-test"
        );

        assertEquals("fallback-ok", result);
        Map<String, Object> stats = service.snapshotStats();
        assertEquals(1L, stats.get("routeFallbackCount"));
        assertEquals(1L, stats.get("firstPacketFailureCount"));
        assertEquals(0L, stats.get("firstPacketTimeoutCount"));
    }

    @Test
    void shouldExposeHealthStateSnapshotInStats() {
        ModelRoutingProperties properties = new ModelRoutingProperties();
        ModelSelector selector = new ModelSelector(properties);
        ModelHealthStore healthStore = new ModelHealthStore(properties);
        ModelRoutingExecutor executor = new ModelRoutingExecutor(healthStore);
        ModelProbeAwaiter awaiter = new ModelProbeAwaiter(properties, Runnable::run);
        DynamicModelFactory factory = mock(DynamicModelFactory.class);
        ChatModel fallbackModel = mock(ChatModel.class);
        Executor asyncExecutor = Runnable::run;
        RoutingChatService service = new RoutingChatService(asyncExecutor, properties, selector, executor, healthStore, factory, awaiter, fallbackModel);

        healthStore.markFailure("m-observe", "test failure");
        Map<String, Object> stats = service.snapshotStats();

        assertTrue(stats.containsKey("states"));
        assertTrue(stats.containsKey("details"));
        assertTrue(stats.containsKey("health"));
    }
}
