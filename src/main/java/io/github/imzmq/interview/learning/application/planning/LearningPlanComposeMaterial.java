package io.github.imzmq.interview.learning.application.planning;

public record LearningPlanComposeMaterial(String plan) {
    public LearningPlanComposeMaterial {
        if (plan == null || plan.isBlank()) {
            throw new IllegalArgumentException("EMPTY_PLAN");
        }
    }
}
