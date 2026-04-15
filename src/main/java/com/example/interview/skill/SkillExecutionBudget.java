package com.example.interview.skill;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class SkillExecutionBudget {

    private final AtomicInteger remainingSkillExecutions;
    private final AtomicInteger remainingMcpCalls;
    private final AtomicLong remainingLatencyMs;

    public SkillExecutionBudget(int maxSkillExecutions, int maxMcpCalls, long extraLatencyBudgetMs) {
        this.remainingSkillExecutions = new AtomicInteger(Math.max(0, maxSkillExecutions));
        this.remainingMcpCalls = new AtomicInteger(Math.max(0, maxMcpCalls));
        this.remainingLatencyMs = new AtomicLong(Math.max(0L, extraLatencyBudgetMs));
    }

    public boolean tryConsumeSkillExecution() {
        return tryConsume(remainingSkillExecutions);
    }

    public boolean tryConsumeMcpCall() {
        return tryConsume(remainingMcpCalls);
    }

    public long remainingLatencyMs() {
        return Math.max(0L, remainingLatencyMs.get());
    }

    public void consumeLatency(long latencyMs) {
        if (latencyMs <= 0L) {
            return;
        }
        remainingLatencyMs.updateAndGet(current -> Math.max(0L, current - latencyMs));
    }

    private boolean tryConsume(AtomicInteger counter) {
        while (true) {
            int current = counter.get();
            if (current <= 0) {
                return false;
            }
            if (counter.compareAndSet(current, current - 1)) {
                return true;
            }
        }
    }
}
