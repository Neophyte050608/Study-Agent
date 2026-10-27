package io.github.imzmq.interview.learning.application.contract.extension;

import io.github.imzmq.interview.learning.application.contract.result.LearningPlanEvidence;
import java.util.List;

/** Read-only evidence source; must not invoke skills or write business state. */
public interface LearningPlanEvidenceReader {
    List<LearningPlanEvidence> read(String topic);
}
