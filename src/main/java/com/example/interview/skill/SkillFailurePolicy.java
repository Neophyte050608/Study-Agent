package com.example.interview.skill;

public record SkillFailurePolicy(
        int maxAttempts,
        long timeoutMs,
        long backoffMillis,
        int circuitBreakerFailureThreshold,
        long circuitBreakerOpenMillis,
        SkillFailureFallbackMode fallbackMode
) {
}
