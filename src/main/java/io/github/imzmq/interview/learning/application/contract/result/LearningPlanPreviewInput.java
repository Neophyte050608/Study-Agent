package io.github.imzmq.interview.learning.application.contract.result;

import java.util.List;

public record LearningPlanPreviewInput(String topic, String request, String profileContext,
                                       List<LearningPlanEvidence> evidence, List<String> degradations) {
    public LearningPlanPreviewInput { evidence = List.copyOf(evidence); degradations = List.copyOf(degradations); }
}
