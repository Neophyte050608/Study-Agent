package io.github.imzmq.interview.config.skill;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.skill.execution")
public class SkillExecutionProperties {

    private int maxSkillExecutions = 3;
    private int maxMcpCalls = 2;
    private long extraLatencyBudgetMs = 3000L;
    private int defaultMaxAttempts = 2;
    private long defaultBackoffMillis = 200L;
    private long defaultTimeoutMs = 2000L;
    private int circuitBreakerFailureThreshold = 5;
    private long circuitBreakerOpenMillis = 60000L;

    public int getMaxSkillExecutions() {
        return maxSkillExecutions;
    }

    public void setMaxSkillExecutions(int maxSkillExecutions) {
        this.maxSkillExecutions = Math.max(1, maxSkillExecutions);
    }

    public int getMaxMcpCalls() {
        return maxMcpCalls;
    }

    public void setMaxMcpCalls(int maxMcpCalls) {
        this.maxMcpCalls = Math.max(0, maxMcpCalls);
    }

    public long getExtraLatencyBudgetMs() {
        return extraLatencyBudgetMs;
    }

    public void setExtraLatencyBudgetMs(long extraLatencyBudgetMs) {
        this.extraLatencyBudgetMs = Math.max(0L, extraLatencyBudgetMs);
    }

    public int getDefaultMaxAttempts() {
        return defaultMaxAttempts;
    }

    public void setDefaultMaxAttempts(int defaultMaxAttempts) {
        this.defaultMaxAttempts = Math.max(1, defaultMaxAttempts);
    }

    public long getDefaultBackoffMillis() {
        return defaultBackoffMillis;
    }

    public void setDefaultBackoffMillis(long defaultBackoffMillis) {
        this.defaultBackoffMillis = Math.max(0L, defaultBackoffMillis);
    }

    public long getDefaultTimeoutMs() {
        return defaultTimeoutMs;
    }

    public void setDefaultTimeoutMs(long defaultTimeoutMs) {
        this.defaultTimeoutMs = Math.max(100L, defaultTimeoutMs);
    }

    public int getCircuitBreakerFailureThreshold() {
        return circuitBreakerFailureThreshold;
    }

    public void setCircuitBreakerFailureThreshold(int circuitBreakerFailureThreshold) {
        this.circuitBreakerFailureThreshold = Math.max(1, circuitBreakerFailureThreshold);
    }

    public long getCircuitBreakerOpenMillis() {
        return circuitBreakerOpenMillis;
    }

    public void setCircuitBreakerOpenMillis(long circuitBreakerOpenMillis) {
        this.circuitBreakerOpenMillis = Math.max(1000L, circuitBreakerOpenMillis);
    }
}

