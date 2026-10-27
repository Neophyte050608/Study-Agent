package io.github.imzmq.interview.learning.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.learning.application.contract.result.DurableLearningPlanView;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.List;
import io.github.imzmq.interview.agent.application.contract.execution.DagNodeStatus;
import io.github.imzmq.interview.learning.application.contract.result.LearningPlanPreview;
import io.github.imzmq.interview.learning.application.contract.result.LearningPlanEvidence;
import static org.assertj.core.api.Assertions.assertThat;

class LearningPlanTaskResponseTest {
    @Test
    void mapsPublicApplicationViewToApiOwnedResponse() throws Exception {
        var view = new DurableLearningPlanView("task", "QUEUED", 2, null, null,
                Map.of("profile", new DurableLearningPlanView.NodeView("PENDING", 0, "PENDING")));
        var response = LearningPlanTaskResponse.from(view);
        assertThat(response.taskId()).isEqualTo("task");
        assertThat(response.revision()).isEqualTo(2);
        assertThat(response.nodes().get("profile").status()).isEqualTo("PENDING");
        String json = new ObjectMapper().writeValueAsString(response);
        assertThat(json).contains("\"attempts\":0").doesNotContain("resultJson", "workflowType", "owner", "lease");
    }
    @Test
    void mapsFinalPlanAndReferencesWithoutRuntimeTypes() throws Exception {
        var preview = new LearningPlanPreview("COMPLETED", "Study graphs", true, true,
                List.of(new LearningPlanEvidence("doc", "Graph basics", "Dependencies")), List.of(),
                Map.of("compose", new LearningPlanPreview.NodeSummary(DagNodeStatus.SUCCEEDED, "OK")));
        var response = LearningPlanTaskResponse.from(new DurableLearningPlanView("task", "COMPLETED", 3,
                preview, null, Map.of()));
        assertThat(response.result().plan()).isEqualTo("Study graphs");
        assertThat(response.result().evidence().get(0).documentId()).isEqualTo("doc");
        assertThat(response.result().nodes().get("compose").status()).isEqualTo("SUCCEEDED");
        String json = new ObjectMapper().writeValueAsString(response);
        assertThat(json).contains("\"personalized\":true", "\"evidenceAvailable\":true")
                .doesNotContain("resultJson", "workflowType", "owner", "lease");
    }
}
