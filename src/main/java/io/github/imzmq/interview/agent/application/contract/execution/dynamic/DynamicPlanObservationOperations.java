package io.github.imzmq.interview.agent.application.contract.execution.dynamic;

/** Public application boundary for content-free dynamic-planning observations. */
public interface DynamicPlanObservationOperations {
    void requested(String plannerType, String plannerVersion);

    void generated(String plannerType, String plannerVersion,
                   ValidatedDynamicTaskPlan plan, String digest, long durationMs);

    void rejected(String plannerType, String plannerVersion, String errorCode, long durationMs);

    void compiled(String plannerType, String plannerVersion,
                  ValidatedDynamicTaskPlan plan, String digest, long durationMs);

    void fallback(String plannerType, String plannerVersion, String errorCode);

    void shadowCompared(String plannerType, String plannerVersion,
                        String ruleDigest, String shadowDigest,
                        boolean same, int nodeDelta, long durationMs);
}
