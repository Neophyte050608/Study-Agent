package com.example.interview.modelrouting;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ModelHealthStore {

    private final ModelRoutingProperties properties;
    private final Map<String, HealthState> states = new ConcurrentHashMap<>();
    private final AtomicLong openRejectCount = new AtomicLong(0);
    private final AtomicLong openTransitionCount = new AtomicLong(0);

    public ModelHealthStore(ModelRoutingProperties properties) {
        this.properties = properties;
    }

    public ModelCircuitState stateOf(String candidateName) {
        HealthState state = states.computeIfAbsent(candidateName, ignored -> new HealthState());
        synchronized (state) {
            long now = System.currentTimeMillis();
            if (state.circuitState == ModelCircuitState.OPEN && now >= state.openUntilEpochMs) {
                state.circuitState = ModelCircuitState.HALF_OPEN;
                state.halfOpenTrialCount.set(0);
            }
            return state.circuitState;
        }
    }

    public boolean canTry(String candidateName) {
        HealthState state = states.computeIfAbsent(candidateName, ignored -> new HealthState());
        synchronized (state) {
            ModelCircuitState current = stateOf(candidateName);
            if (current == ModelCircuitState.CLOSED) {
                return true;
            }
            if (current == ModelCircuitState.OPEN) {
                openRejectCount.incrementAndGet();
                return false;
            }
            int maxTrials = Math.max(1, properties.getCircuitBreaker().getHalfOpenMaxTrials());
            return state.halfOpenTrialCount.get() < maxTrials;
        }
    }

    public void markHalfOpenTrial(String candidateName) {
        HealthState state = states.computeIfAbsent(candidateName, ignored -> new HealthState());
        synchronized (state) {
            if (stateOf(candidateName) == ModelCircuitState.HALF_OPEN) {
                state.halfOpenTrialCount.incrementAndGet();
            }
        }
    }

    public void markSuccess(String candidateName) {
        HealthState state = states.computeIfAbsent(candidateName, ignored -> new HealthState());
        synchronized (state) {
            state.failureCount.set(0);
            state.halfOpenTrialCount.set(0);
            state.circuitState = ModelCircuitState.CLOSED;
            state.openUntilEpochMs = 0;
        }
    }

    public void markFailure(String candidateName) {
        HealthState state = states.computeIfAbsent(candidateName, ignored -> new HealthState());
        synchronized (state) {
            int threshold = Math.max(1, properties.getCircuitBreaker().getFailureThreshold());
            ModelCircuitState current = stateOf(candidateName);
            if (current == ModelCircuitState.HALF_OPEN) {
                openCircuit(state);
                return;
            }
            int failed = state.failureCount.incrementAndGet();
            if (failed >= threshold) {
                openCircuit(state);
            }
        }
    }

    public Map<String, ModelCircuitState> snapshotStates() {
        return states.keySet().stream().collect(java.util.stream.Collectors.toMap(key -> key, this::stateOf));
    }

    public Map<String, Object> snapshotMetrics() {
        return Map.of(
                "openRejectCount", openRejectCount.get(),
                "openTransitionCount", openTransitionCount.get()
        );
    }

    public Map<String, Map<String, Object>> snapshotDetails() {
        Map<String, Map<String, Object>> result = new ConcurrentHashMap<>();
        for (Map.Entry<String, HealthState> entry : states.entrySet()) {
            HealthState state = entry.getValue();
            synchronized (state) {
                result.put(entry.getKey(), Map.of(
                        "state", stateOf(entry.getKey()).name(),
                        "failureCount", state.failureCount.get(),
                        "halfOpenTrialCount", state.halfOpenTrialCount.get(),
                        "openUntilEpochMs", state.openUntilEpochMs
                ));
            }
        }
        return result;
    }

    private void openCircuit(HealthState state) {
        state.circuitState = ModelCircuitState.OPEN;
        state.failureCount.set(0);
        state.halfOpenTrialCount.set(0);
        state.openUntilEpochMs = System.currentTimeMillis() + Math.max(1000L, properties.getCircuitBreaker().getOpenDurationMs());
        openTransitionCount.incrementAndGet();
    }

    private static class HealthState {
        private ModelCircuitState circuitState = ModelCircuitState.CLOSED;
        private final AtomicInteger failureCount = new AtomicInteger(0);
        private final AtomicInteger halfOpenTrialCount = new AtomicInteger(0);
        private long openUntilEpochMs = 0;
    }
}
