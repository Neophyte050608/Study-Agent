package io.github.imzmq.interview.learning.application.planning;

import io.github.imzmq.interview.learning.application.contract.result.LearningPlanEvidence;

import java.util.List;

public record LearningPlanEvidenceMaterial(List<LearningPlanEvidence> evidence) {
    public LearningPlanEvidenceMaterial {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
