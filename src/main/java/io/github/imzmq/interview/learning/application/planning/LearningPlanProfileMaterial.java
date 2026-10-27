package io.github.imzmq.interview.learning.application.planning;

public record LearningPlanProfileMaterial(String text) {
    public LearningPlanProfileMaterial {
        text = text == null ? "" : text;
    }
}
