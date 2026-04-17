package io.github.imzmq.interview.skill.policy;

public record SkillFailurePolicy(
        int maxAttempts,
        long timeoutMs,
        long backoffMillis,
        int circuitBreakerFailureThreshold,
        long circuitBreakerOpenMillis,
        SkillFailureFallbackMode fallbackMode
) {
}

