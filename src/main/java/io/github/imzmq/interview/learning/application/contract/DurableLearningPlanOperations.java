package io.github.imzmq.interview.learning.application.contract;

import io.github.imzmq.interview.learning.application.contract.result.DurableLearningPlanView;
import java.util.Set;

/** Owner must come from the caller's trusted context, not a client-supplied identifier. */
public interface DurableLearningPlanOperations {
    DurableLearningPlanView submit(String owner, String key, String topic, String request);
    DurableLearningPlanView query(String owner, String taskId);
    DurableLearningPlanView retry(String owner, String taskId, int expectedRevision, Set<String> nodes);
    void delete(String owner, String taskId);
}
