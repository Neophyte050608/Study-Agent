package io.github.imzmq.interview.modelrouting;

import io.github.imzmq.interview.modelrouting.core.ModelRouteType;
import io.github.imzmq.interview.modelrouting.core.ModelRoutingCandidate;
import io.github.imzmq.interview.modelrouting.core.ModelRoutingProperties;
import io.github.imzmq.interview.modelrouting.core.RoutingChatService;
import io.github.imzmq.interview.modelrouting.core.TimeoutHint;
import io.github.imzmq.interview.modelrouting.execution.ModelRoutingExecutor;
import io.github.imzmq.interview.modelrouting.execution.ModelSelector;
import io.github.imzmq.interview.modelrouting.provider.YamlCandidateProvider;
import io.github.imzmq.interview.modelrouting.probe.ModelProbeAwaiter;
import io.github.imzmq.interview.modelrouting.state.ModelHealthStore;
import io.github.imzmq.interview.modelruntime.application.DynamicModelFactory;
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

        ModelSelector selector = new ModelSelector(properties, new YamlCandidateProvider(properties));
        ModelHealthStore healthStore = new ModelHealthStore(properties);
        ModelRoutingExecutor executor = new ModelRoutingExecutor(healthStore);
        ModelProbeAwaiter awaiter = new ModelProbeAwaiter(properties, Runnable::run);
        DynamicModelFactory factory = mock(DynamicModelFactory.class);
        when(factory.getByCandidate(new ModelRoutingCandidate("m1", "openai", "", "", 1, false, "", "", "", "YAML"))).thenReturn(null);
        ChatModel fallbackModel = mock(ChatModel.class);
        Executor asyncExecutor = Runnable::run;
        RoutingChatService service = new RoutingChatService(asyncExecutor, properties, selector, executor, healthStore, factory, awaiter, fallbackModel);

        String result = service.callWithFirstPacketProbeSupplier(
                () -> "fallback-ok",
                "prompt",
                ModelRouteType.GENERAL,
                TimeoutHint.NORMAL,
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
        ModelSelector selector = new ModelSelector(properties, new YamlCandidateProvider(properties));
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


