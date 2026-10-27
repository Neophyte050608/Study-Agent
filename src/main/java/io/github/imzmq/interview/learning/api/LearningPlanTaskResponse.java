package io.github.imzmq.interview.learning.api;

import io.github.imzmq.interview.learning.application.contract.result.DurableLearningPlanView;
import io.github.imzmq.interview.learning.application.contract.result.LearningPlanPreview;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** HTTP-owned projection of the already validated Learning application contract. */
public record LearningPlanTaskResponse(String taskId, String status, int revision, PreviewResponse result,
                                       String errorCode, Map<String, NodeResponse> nodes) {
    public LearningPlanTaskResponse {
        nodes = Map.copyOf(nodes);
    }

    static LearningPlanTaskResponse from(DurableLearningPlanView view) {
        Map<String, NodeResponse> nodes = new LinkedHashMap<>();
        view.nodes().forEach((id, node) -> nodes.put(id,
                new NodeResponse(node.status(), node.attempts(), node.code())));
        return new LearningPlanTaskResponse(view.taskId(), view.status(), view.revision(),
                view.result() == null ? null : PreviewResponse.from(view.result()), view.errorCode(), nodes);
    }

    public record NodeResponse(String status, int attempts, String code) { }

    public record PreviewResponse(String status, String plan, boolean personalized, boolean evidenceAvailable,
                                  List<EvidenceResponse> evidence, List<String> degradations,
                                  Map<String, SummaryResponse> nodes) {
        public PreviewResponse {
            evidence = List.copyOf(evidence);
            degradations = List.copyOf(degradations);
            nodes = Map.copyOf(nodes);
        }

        static PreviewResponse from(LearningPlanPreview preview) {
            Map<String, SummaryResponse> nodes = new LinkedHashMap<>();
            preview.nodes().forEach((id, node) -> nodes.put(id,
                    new SummaryResponse(String.valueOf(node.status()), node.code())));
            var evidence = preview.evidence().stream()
                    .map(item -> new EvidenceResponse(item.documentId(), item.title(), item.excerpt())).toList();
            return new PreviewResponse(preview.status(), preview.plan(), preview.personalized(),
                    preview.evidenceAvailable(), evidence, preview.degradations(), nodes);
        }
    }

    public record EvidenceResponse(String documentId, String title, String excerpt) { }
    public record SummaryResponse(String status, String code) { }
}
