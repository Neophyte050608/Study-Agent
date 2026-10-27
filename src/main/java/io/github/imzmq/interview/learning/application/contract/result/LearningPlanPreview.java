package io.github.imzmq.interview.learning.application.contract.result;

import io.github.imzmq.interview.agent.application.contract.execution.DagNodeStatus;
import java.util.List;
import java.util.Map;

/** Node summaries intentionally omit raw profile/observations and exceptions. */
public record LearningPlanPreview(String status, String plan, boolean personalized, boolean evidenceAvailable,
                                  List<LearningPlanEvidence> evidence, List<String> degradations,
                                  Map<String, NodeSummary> nodes) {
    public LearningPlanPreview {
        evidence = List.copyOf(evidence); degradations = List.copyOf(degradations); nodes = Map.copyOf(nodes);
    }
    public record NodeSummary(DagNodeStatus status, String code) { }
}
