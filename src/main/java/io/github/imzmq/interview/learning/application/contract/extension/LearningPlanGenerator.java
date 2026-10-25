package io.github.imzmq.interview.learning.application.contract.extension;

/** Learning-owned generation extension implemented by the module that supplies plan content. */
public interface LearningPlanGenerator {

    String generateLearningPlan(String topic, String weakPoint, String recentPerformance);
}
