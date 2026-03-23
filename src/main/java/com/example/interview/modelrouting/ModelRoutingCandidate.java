package com.example.interview.modelrouting;

public record ModelRoutingCandidate(
        String name,
        String provider,
        String model,
        String beanName,
        int priority,
        boolean supportsThinking
) {
}
