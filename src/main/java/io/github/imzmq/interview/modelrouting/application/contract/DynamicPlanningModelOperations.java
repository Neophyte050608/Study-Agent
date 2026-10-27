package io.github.imzmq.interview.modelrouting.application.contract;

@FunctionalInterface
public interface DynamicPlanningModelOperations {
    String generatePlan(String systemPrompt, String userPrompt);
}
