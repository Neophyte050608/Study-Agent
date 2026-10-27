package io.github.imzmq.interview.learning.application.contract.result;

import java.util.Map;

/** Owner-scoped safe task snapshot; no runtime JSON or attempt inputs cross this boundary. */
public record DurableLearningPlanView(String taskId, String status, int revision, LearningPlanPreview result,
                                     String errorCode, Map<String, NodeView> nodes) {
    public DurableLearningPlanView {
        nodes = Map.copyOf(nodes);
    }

    public record NodeView(String status, int attempts, String code) { }
}
