package io.github.imzmq.interview.skill.runtime;

import io.github.imzmq.interview.config.skill.SkillExecutionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import io.github.imzmq.interview.skill.core.ExecutableSkill;
import io.github.imzmq.interview.skill.core.SkillDefinition;
import io.github.imzmq.interview.skill.core.SkillExecutionBudget;
import io.github.imzmq.interview.skill.core.SkillExecutionContext;
import io.github.imzmq.interview.skill.core.SkillExecutionResult;
import io.github.imzmq.interview.skill.core.SkillExecutionStatus;
import io.github.imzmq.interview.skill.policy.SkillFailureFallbackMode;
import io.github.imzmq.interview.skill.policy.SkillFailurePolicy;
import io.github.imzmq.interview.skill.policy.SkillPolicyException;

@Service
public class SkillExecutor {

    private static final Logger logger = LoggerFactory.getLogger(SkillExecutor.class);

    private final SkillExecutionProperties properties;
    private final SkillTelemetryRecorder telemetryRecorder;
    private final ConcurrentHashMap<String, CircuitBreakerState> circuitStates = new ConcurrentHashMap<>();

    public SkillExecutor(SkillExecutionProperties properties) {
        this(properties, new SkillTelemetryRecorder());
    }

    @Autowired
    public SkillExecutor(SkillExecutionProperties properties, SkillTelemetryRecorder telemetryRecorder) {
        this.properties = properties;
        this.telemetryRecorder = telemetryRecorder;
    }

    public SkillExecutionResult execute(ExecutableSkill skill, SkillExecutionContext context) {
        if (skill == null || skill.definition() == null) {
            return SkillExecutionResult.skipped("unknown", "skill_not_registered");
        }
        SkillDefinition definition = skill.definition();
        SkillFailurePolicy policy = mergedPolicy(definition.failurePolicy());
        SkillExecutionBudget budget = context == null ? null : context.budget();
        if (budget != null && !budget.tryConsumeSkillExecution()) {
            SkillExecutionResult result = SkillExecutionResult.skipped(definition.id(), "skill_budget_exhausted");
            recordTelemetry(definition.id(), result, 0L, context);
            return result;
        }
        CircuitBreakerState state = circuitStates.computeIfAbsent(definition.id(), ignored -> new CircuitBreakerState());
        if (state.isOpen()) {
            SkillExecutionResult result = fallbackResult(definition, "circuit_open", 0, policy);
            recordTelemetry(definition.id(), result, 0L, context);
            return result;
        }
        RuntimeException lastError = null;
        int attempts = Math.max(1, policy.maxAttempts());
        long totalLatency = 0L;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            long timeoutMs = resolveTimeout(policy, budget);
            if (timeoutMs <= 0L) {
                SkillExecutionResult result = fallbackResult(definition, "latency_budget_exhausted", attempt - 1, policy);
                recordTelemetry(definition.id(), result, totalLatency, context);
                return result;
            }
            long start = System.currentTimeMillis();
            boolean latencyConsumed = false;
            try {
                SkillExecutionResult result = CompletableFuture
                        .supplyAsync(() -> skill.execute(context))
                        .get(timeoutMs, TimeUnit.MILLISECONDS);
                long cost = System.currentTimeMillis() - start;
                totalLatency += cost;
                if (budget != null) {
                    budget.consumeLatency(cost);
                    latencyConsumed = true;
                }
                state.reset();
                if (result == null) {
                    SkillExecutionResult success = SkillExecutionResult.success(definition.id(), java.util.Map.of(), attempt, java.util.List.of());
                    recordTelemetry(definition.id(), success, totalLatency, context);
                    return success;
                }
                SkillExecutionResult success = new SkillExecutionResult(
                        definition.id(),
                        result.status(),
                        result.output(),
                        attempt,
                        result.fallbackUsed(),
                        result.message(),
                        result.toolCalls()
                );
                recordTelemetry(definition.id(), success, totalLatency, context);
                return success;
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("线程中断", interruptedException);
            } catch (TimeoutException timeoutException) {
                lastError = new SkillPolicyException("skill_timeout", timeoutException, true);
            } catch (ExecutionException executionException) {
                lastError = wrapExecutionException(executionException);
            } catch (CompletionException completionException) {
                lastError = wrapThrowable(completionException.getCause());
            } catch (RuntimeException runtimeException) {
                lastError = wrapThrowable(runtimeException);
            } finally {
                long cost = System.currentTimeMillis() - start;
                totalLatency += latencyConsumed ? 0L : cost;
                if (budget != null && !latencyConsumed) {
                    budget.consumeLatency(cost);
                }
            }

            if (lastError == null) {
                lastError = new IllegalStateException("skill_failed");
            }
            logger.warn("技能执行失败: skill={}, attempt={}, reason={}", definition.id(), attempt, lastError.getMessage());
            if (!isRetryable(lastError) || attempt >= attempts) {
                break;
            }
            long backoff = policy.backoffMillis() * attempt + ThreadLocalRandom.current().nextLong(30L, 121L);
            sleep(backoff, budget);
        }
        state.recordFailure(policy);
        SkillExecutionResult result = fallbackResult(definition, lastError == null ? "skill_failed" : lastError.getMessage(), attempts, policy);
        recordTelemetry(definition.id(), result, totalLatency, context);
        return result;
    }

    private void recordTelemetry(String skillId, SkillExecutionResult result, long latencyMs, SkillExecutionContext context) {
        String traceId = safeText(context == null ? null : context.traceId());
        String operator = safeText(context == null ? null : context.operator());
        telemetryRecorder.record(
                skillId,
                result == null ? SkillExecutionStatus.FAILED : result.status(),
                result == null ? 0 : result.attempts(),
                result != null && result.fallbackUsed(),
                result == null ? "skill_result_missing" : result.message(),
                latencyMs,
                Map.of(
                        "traceId", traceId,
                        "operator", operator
                )
        );
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private SkillFailurePolicy mergedPolicy(SkillFailurePolicy policy) {
        if (policy == null) {
            return new SkillFailurePolicy(
                    properties.getDefaultMaxAttempts(),
                    properties.getDefaultTimeoutMs(),
                    properties.getDefaultBackoffMillis(),
                    properties.getCircuitBreakerFailureThreshold(),
                    properties.getCircuitBreakerOpenMillis(),
                    SkillFailureFallbackMode.SKIP_SKILL
            );
        }
        return new SkillFailurePolicy(
                policy.maxAttempts() <= 0 ? properties.getDefaultMaxAttempts() : policy.maxAttempts(),
                policy.timeoutMs() <= 0 ? properties.getDefaultTimeoutMs() : policy.timeoutMs(),
                policy.backoffMillis() < 0 ? properties.getDefaultBackoffMillis() : policy.backoffMillis(),
                policy.circuitBreakerFailureThreshold() <= 0 ? properties.getCircuitBreakerFailureThreshold() : policy.circuitBreakerFailureThreshold(),
                policy.circuitBreakerOpenMillis() <= 0 ? properties.getCircuitBreakerOpenMillis() : policy.circuitBreakerOpenMillis(),
                policy.fallbackMode() == null ? SkillFailureFallbackMode.SKIP_SKILL : policy.fallbackMode()
        );
    }

    private long resolveTimeout(SkillFailurePolicy policy, SkillExecutionBudget budget) {
        long timeoutMs = Math.max(100L, policy.timeoutMs());
        if (budget == null) {
            return timeoutMs;
        }
        return Math.min(timeoutMs, budget.remainingLatencyMs());
    }

    private SkillExecutionResult fallbackResult(SkillDefinition definition, String message, int attempts, SkillFailurePolicy policy) {
        if (policy.fallbackMode() == SkillFailureFallbackMode.INSTRUCTION_ONLY) {
            return SkillExecutionResult.fallback(definition.id(), "instruction_only:" + message, attempts);
        }
        if (policy.fallbackMode() == SkillFailureFallbackMode.USE_CACHED_RESULT) {
            return SkillExecutionResult.fallback(definition.id(), "cached_result_unavailable:" + message, attempts);
        }
        return SkillExecutionResult.fallback(definition.id(), message, attempts);
    }

    private RuntimeException wrapExecutionException(ExecutionException executionException) {
        Throwable cause = executionException.getCause();
        return wrapThrowable(cause == null ? executionException : cause);
    }

    private RuntimeException wrapThrowable(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new IllegalStateException(throwable == null ? "skill_failed" : throwable.getMessage(), throwable);
    }

    private boolean isRetryable(Throwable error) {
        if (error instanceof SkillPolicyException policyException) {
            return policyException.isRetryable();
        }
        if (error instanceof ResourceAccessException) {
            return true;
        }
        if (error instanceof RestClientResponseException responseException) {
            HttpStatusCode statusCode = responseException.getStatusCode();
            return statusCode.is5xxServerError() || statusCode.value() == 429;
        }
        Throwable cause = error == null ? null : error.getCause();
        if (cause instanceof SocketTimeoutException || cause instanceof TimeoutException) {
            return true;
        }
        if (error instanceof IllegalArgumentException) {
            return false;
        }
        String message = error == null || error.getMessage() == null ? "" : error.getMessage().toLowerCase();
        return message.contains("timeout")
                || message.contains("temporarily")
                || message.contains("too many requests")
                || message.contains("rate limit")
                || message.contains("unavailable")
                || message.contains("busy");
    }

    private void sleep(long backoffMs, SkillExecutionBudget budget) {
        if (backoffMs <= 0L) {
            return;
        }
        try {
            TimeUnit.MILLISECONDS.sleep(backoffMs);
            if (budget != null) {
                budget.consumeLatency(backoffMs);
            }
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("线程中断", interruptedException);
        }
    }

    private static final class CircuitBreakerState {
        private int consecutiveFailures;
        private long openUntilMs;

        synchronized boolean isOpen() {
            long now = System.currentTimeMillis();
            if (openUntilMs > now) {
                return true;
            }
            if (openUntilMs > 0L) {
                openUntilMs = 0L;
                consecutiveFailures = 0;
            }
            return false;
        }

        synchronized void reset() {
            consecutiveFailures = 0;
            openUntilMs = 0L;
        }

        synchronized void recordFailure(SkillFailurePolicy policy) {
            consecutiveFailures++;
            if (consecutiveFailures >= policy.circuitBreakerFailureThreshold()) {
                openUntilMs = System.currentTimeMillis() + Duration.ofMillis(policy.circuitBreakerOpenMillis()).toMillis();
            }
        }
    }
}


