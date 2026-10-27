package io.github.imzmq.interview.learning.application.contract.extension;

import io.github.imzmq.interview.learning.application.contract.result.LearningPlanPreviewInput;

/** Content-only generation from supplied materials, without retrieval or tools. */
public interface LearningPlanPreviewGenerator {
    String generate(LearningPlanPreviewInput input);
}
