package io.github.imzmq.interview.tools.skill.policy;

public record SkillFailurePolicy(
        int maxAttempts,
        long timeoutMs,
        long backoffMillis,
        int circuitBreakerFailureThreshold,
        long circuitBreakerOpenMillis,
        SkillFailureFallbackMode fallbackMode
) {
}
