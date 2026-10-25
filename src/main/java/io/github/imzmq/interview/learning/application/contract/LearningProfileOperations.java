package io.github.imzmq.interview.learning.application.contract;

import io.github.imzmq.interview.learning.application.contract.result.TopicCapabilityCurve;
import io.github.imzmq.interview.learning.application.contract.result.TrainingProfileSnapshot;

import java.util.List;
import java.util.Map;

/** Public learning-profile use cases consumed by other business modules. */
public interface LearningProfileOperations {

    String normalizeUserId(String userId);

    boolean upsertEvent(LearningEvent input);

    TrainingProfileSnapshot snapshot(String userId);

    String snapshotForPrompt(String userId, String currentTopic);

    String recommend(String userId, String mode);

    List<Map<String, Object>> listEvents(String userId, int limit);

    Map<String, Object> overview(String userId);

    TopicCapabilityCurve getTopicCapabilityCurve(String userId, String topic);
}
