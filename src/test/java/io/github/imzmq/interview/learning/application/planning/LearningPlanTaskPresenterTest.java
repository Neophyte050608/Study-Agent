package io.github.imzmq.interview.learning.application.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.agent.application.contract.execution.durable.DurableTaskView;
import io.github.imzmq.interview.learning.application.contract.LearningPlanAccessException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LearningPlanTaskPresenterTest {
    private static final String ID = "123e4567-e89b-12d3-a456-426614174000";
    private final LearningPlanTaskPresenter presenter = new LearningPlanTaskPresenter(new ObjectMapper());

    @Test
    void acceptsHistoricalV1OnlyWithTheFixedGraph() {
        assertThat(presenter.present(task(1, "COMPLETED", fixedNodes(),
                completedResult(Set.of("profile", "evidence", "compose"), true, true))).nodes())
                .containsOnlyKeys("profile", "evidence", "compose");

        assertInvalid(task(1, "COMPLETED", profileNodes(),
                completedResult(Set.of("profile", "compose"), true, false)));
    }

    @Test
    void acceptsEachSupportedV2GraphShape() {
        assertThat(presenter.present(task(2, "COMPLETED", profileNodes(),
                completedResult(Set.of("profile", "compose"), true, false))).result().degradations())
                .isEmpty();
        assertThat(presenter.present(task(2, "COMPLETED", evidenceNodes(),
                completedResult(Set.of("evidence", "compose"), false, true))).result().degradations())
                .isEmpty();
        assertThat(presenter.present(task(2, "COMPLETED", fixedNodes(),
                completedResult(Set.of("profile", "evidence", "compose"), true, true))).nodes())
                .containsOnlyKeys("profile", "evidence", "compose");
    }

    @Test
    void rejectsUnsupportedV2Topology() {
        assertInvalid(task(2, "COMPLETED", nodes("compose"),
                completedResult(Set.of("compose"), false, false)));
        assertInvalid(task(2, "COMPLETED", nodes("profile"), null));
        assertInvalid(task(2, "COMPLETED", nodes("profile", "unknown", "compose"), null));
    }

    @Test
    void requiresResultNodeKeysToExactlyMatchThePersistedTaskGraph() {
        assertInvalid(task(2, "COMPLETED", profileNodes(),
                completedResult(Set.of("profile", "evidence", "compose"), true, true)));
        assertInvalid(task(2, "COMPLETED", fixedNodes(),
                completedResult(Set.of("profile", "compose"), true, false)));
    }

    @Test
    void doesNotTurnAnUnplannedMaterialIntoADegradation() {
        var profileOnly = presenter.present(task(2, "COMPLETED", profileNodes(),
                completedResult(Set.of("profile", "compose"), true, false))).result();
        assertThat(profileOnly.evidence()).isEmpty();
        assertThat(profileOnly.evidenceAvailable()).isFalse();
        assertThat(profileOnly.degradations()).isEmpty();

        var evidenceOnly = presenter.present(task(2, "COMPLETED", evidenceNodes(),
                completedResult(Set.of("evidence", "compose"), false, true))).result();
        assertThat(evidenceOnly.personalized()).isFalse();
        assertThat(evidenceOnly.degradations()).isEmpty();
    }

    @Test
    void preservesDegradationForAnIncludedFailedOrEmptyMaterial() {
        Map<String, DurableTaskView.NodeView> failedProfile = new LinkedHashMap<>(profileNodes());
        failedProfile.put("profile", node("FAILED"));
        String unavailable = result("DEGRADED", Set.of("profile", "compose"), false, false,
                "[\"PROFILE_UNAVAILABLE\"]", "FAILED");
        assertThat(presenter.present(task(2, "DEGRADED", failedProfile, unavailable))
                .result().degradations()).containsExactly("PROFILE_UNAVAILABLE");

        String emptyEvidence = result("DEGRADED", Set.of("evidence", "compose"), false, false,
                "[\"EVIDENCE_EMPTY\"]", "SUCCEEDED");
        assertThat(presenter.present(task(2, "DEGRADED", evidenceNodes(), emptyEvidence))
                .result().degradations()).containsExactly("EVIDENCE_EMPTY");
    }

    private DurableTaskView task(
            int version,
            String status,
            Map<String, DurableTaskView.NodeView> nodes,
            String result) {
        return new DurableTaskView(ID, status, 2, result, null, nodes,
                "LEARNING_PLAN_PREVIEW", version);
    }

    private Map<String, DurableTaskView.NodeView> fixedNodes() {
        return nodes("profile", "evidence", "compose");
    }

    private Map<String, DurableTaskView.NodeView> profileNodes() {
        return nodes("profile", "compose");
    }

    private Map<String, DurableTaskView.NodeView> evidenceNodes() {
        return nodes("evidence", "compose");
    }

    private Map<String, DurableTaskView.NodeView> nodes(String... ids) {
        Map<String, DurableTaskView.NodeView> result = new LinkedHashMap<>();
        for (String id : ids) result.put(id, node("SUCCEEDED"));
        return result;
    }

    private DurableTaskView.NodeView node(String status) {
        return new DurableTaskView.NodeView(status, 1,
                "SUCCEEDED".equals(status) ? "OK" : "NODE_FAILED");
    }

    private String completedResult(Set<String> ids, boolean personalized, boolean evidence) {
        return result("COMPLETED", ids, personalized, evidence, "[]", "SUCCEEDED");
    }

    private String result(
            String status,
            Set<String> ids,
            boolean personalized,
            boolean evidence,
            String degradations,
            String materialStatus) {
        StringBuilder nodesJson = new StringBuilder();
        for (String id : ids.stream().sorted().toList()) {
            if (!nodesJson.isEmpty()) nodesJson.append(',');
            String nodeStatus = "compose".equals(id) ? "SUCCEEDED" : materialStatus;
            nodesJson.append('\"').append(id).append("\":{\"status\":\"")
                    .append(nodeStatus).append("\",\"code\":\"")
                    .append("SUCCEEDED".equals(nodeStatus) ? "OK" : "NODE_FAILED")
                    .append("\"}");
        }
        String evidenceJson = evidence
                ? "[{\"documentId\":\"doc-1\",\"title\":\"Java\",\"excerpt\":\"Concurrency\"}]"
                : "[]";
        return "{\"status\":\"" + status + "\",\"plan\":\"Three day plan\"," +
                "\"personalized\":" + personalized + ",\"evidenceAvailable\":" + evidence + "," +
                "\"evidence\":" + evidenceJson + ",\"degradations\":" + degradations + "," +
                "\"nodes\":{" + nodesJson + "}}";
    }

    private void assertInvalid(DurableTaskView task) {
        assertThatThrownBy(() -> presenter.present(task))
                .isInstanceOf(LearningPlanAccessException.class)
                .hasMessage("INVALID_EXECUTION_RESULT");
    }
}
