package com.example.interview.modelrouting;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模型健康状态存储与三态熔断器实现。
 * 
 * 【架构演进与技术选型】
 * 1. 痛点：早期的单点 LLM 调用（如只调 OpenAI）非常脆弱，遇到 Rate Limit 或 502 时直接抛错给前端，导致核心业务（面试、出题）不可用。
 * 2. 为什么不用 Spring Cloud Resilience4j？
 *    Resilience4j 主要是基于 HTTP 状态码和异常来做熔断，但在 LLM 场景中，最大的痛点是“首包假死”（连接成功，HTTP 200，但一直不返回流式数据）。
 *    我们需要一套能与“首包超时探测（First-Packet Probe）”深度绑定的自定义熔断状态机。
 * 3. 三态机制流转：
 *    - CLOSED (正常)：请求直接放行。若连续失败达到阈值，切换为 OPEN。
 *    - OPEN (熔断)：直接拒绝请求，走降级逻辑（尝试下一个模型候选）。设置一个冷却时间（如 30 秒）。
 *    - HALF_OPEN (半开)：冷却时间到后，允许放行少量请求（如 1 个）去探活。如果成功，恢复为 CLOSED；如果失败，立刻切回 OPEN。
 * 
 * 维护每个模型候选者的健康状态（CLOSED, OPEN, HALF_OPEN），并控制状态流转。
 */
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
