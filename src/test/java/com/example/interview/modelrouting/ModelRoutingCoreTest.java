package com.example.interview.modelrouting;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelRoutingCoreTest {

    @Test
    void shouldSortByPriorityAndPreferredModel() {
        ModelRoutingProperties properties = new ModelRoutingProperties();
        properties.setDefaultModel("m2");
        ModelRoutingProperties.Candidate c1 = new ModelRoutingProperties.Candidate();
        c1.setName("m1");
        c1.setPriority(10);
        c1.setEnabled(true);
        ModelRoutingProperties.Candidate c2 = new ModelRoutingProperties.Candidate();
        c2.setName("m2");
        c2.setPriority(10);
        c2.setEnabled(true);
        properties.setCandidates(List.of(c1, c2));
        ModelSelector selector = new ModelSelector(properties);

        List<ModelRoutingCandidate> selected = selector.select(ModelRouteType.GENERAL);

        assertEquals("m2", selected.getFirst().name());
        assertEquals("m1", selected.get(1).name());
    }

    @Test
    void shouldOpenAndHalfOpenCircuit() {
        ModelRoutingProperties properties = new ModelRoutingProperties();
        properties.getCircuitBreaker().setFailureThreshold(2);
        properties.getCircuitBreaker().setOpenDurationMs(1000);
        ModelHealthStore store = new ModelHealthStore(properties);

        store.markFailure("m1");
        assertEquals(ModelCircuitState.CLOSED, store.stateOf("m1"));
        store.markFailure("m1");
        assertEquals(ModelCircuitState.OPEN, store.stateOf("m1"));
        try {
            Thread.sleep(1200L);
        } catch (InterruptedException ignored) {
        }
        assertEquals(ModelCircuitState.HALF_OPEN, store.stateOf("m1"));
        store.markSuccess("m1");
        assertEquals(ModelCircuitState.CLOSED, store.stateOf("m1"));
    }

    @Test
    void shouldFallbackToSecondCandidateWhenFirstFails() {
        ModelRoutingProperties properties = new ModelRoutingProperties();
        ModelHealthStore store = new ModelHealthStore(properties);
        ModelRoutingExecutor executor = new ModelRoutingExecutor(store);
        ModelRoutingCandidate first = new ModelRoutingCandidate("m1", "openai", "", "", 10, false);
        ModelRoutingCandidate second = new ModelRoutingCandidate("m2", "openai", "", "", 20, false);
        AtomicInteger attempt = new AtomicInteger(0);

        String result = executor.execute(List.of(first, second), candidate -> {
            if (attempt.getAndIncrement() == 0) {
                throw new IllegalStateException("boom");
            }
            return "ok-" + candidate.name();
        }, "test");

        assertEquals("ok-m2", result);
    }

    @Test
    void shouldThrowWhenAllCandidatesFail() {
        ModelRoutingProperties properties = new ModelRoutingProperties();
        ModelHealthStore store = new ModelHealthStore(properties);
        ModelRoutingExecutor executor = new ModelRoutingExecutor(store);
        ModelRoutingCandidate first = new ModelRoutingCandidate("m1", "openai", "", "", 10, false);

        assertThrows(ModelRoutingException.class, () -> executor.execute(List.of(first), ignored -> {
            throw new IllegalStateException("fail");
        }, "test"));
    }

    @Test
    void shouldExposeCircuitMetricsAndDetails() {
        ModelRoutingProperties properties = new ModelRoutingProperties();
        properties.getCircuitBreaker().setFailureThreshold(1);
        properties.getCircuitBreaker().setOpenDurationMs(1000);
        ModelHealthStore store = new ModelHealthStore(properties);

        store.markFailure("m1");
        store.canTry("m1");
        Map<String, Object> metrics = store.snapshotMetrics();
        Map<String, Map<String, Object>> details = store.snapshotDetails();

        assertEquals(1L, metrics.get("openTransitionCount"));
        assertEquals(1L, metrics.get("openRejectCount"));
        assertEquals("OPEN", String.valueOf(details.get("m1").get("state")));
    }
}
