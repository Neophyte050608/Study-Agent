package io.github.imzmq.interview.learning.application.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.agent.application.contract.execution.durable.DurableExecutionOperations;
import io.github.imzmq.interview.agent.application.contract.execution.durable.DurableTaskView;
import io.github.imzmq.interview.learning.application.contract.LearningPlanAccessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Map;
import java.util.HashSet;
import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DurableLearningPlanServiceTest {
    private static final String ID = "123e4567-e89b-12d3-a456-426614174000";
    private final DurableExecutionOperations execution = mock(DurableExecutionOperations.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final DurableLearningPlanService service = new DurableLearningPlanService(execution, mapper);

    @Test
    void normalizesOnlyAllowedInputAndPreservesTrustedOwner() {
        when(execution.submit(anyString(), anyString(), anyInt(), anyString(), anyString())).thenReturn(task("QUEUED", null));
        assertThat(service.submit("trusted", "key", " Java ", " three days ").taskId()).isEqualTo(ID);
        verify(execution).submit("trusted", "LEARNING_PLAN_PREVIEW", 2, "key", "{\"topic\":\"Java\",\"request\":\"three days\"}");
        doReturn(task("FAILED", null)).when(execution).query("trusted", ID);
        when(execution.retry("trusted", ID, 2, Set.of("evidence"))).thenReturn(task("QUEUED", null));
        service.query("trusted", ID);
        service.retry("trusted", ID, 2, Set.of("evidence"));
        service.delete("trusted", ID);
        verify(execution, times(3)).query("trusted", ID);
        verify(execution).retry("trusted", ID, 2, Set.of("evidence"));
        verify(execution).delete("trusted", ID);
    }

    @Test
    void rejectsInvalidInputBeforeCallingAgent() {
        for (String key : new String[]{"", " ", "x".repeat(121), "key\n"}) {
            assertCode(() -> service.submit("trusted", key, "Java", null), "INVALID_INPUT");
        }
        assertCode(() -> service.submit("", "key", "Java", null), "UNAUTHENTICATED");
        assertCode(() -> service.submit("trusted", "key", " ", null), "INVALID_PREVIEW_TOPIC");
        assertCode(() -> service.submit("trusted", "key", "x".repeat(201), null), "INVALID_PREVIEW_TOPIC");
        assertCode(() -> service.submit("trusted", "key", "Java", "x".repeat(2001)), "INVALID_PREVIEW_REQUEST");
        assertCode(() -> service.query("trusted", "not-a-uuid"), "INVALID_INPUT");
        assertCode(() -> service.retry("trusted", ID, -1, Set.of()), "INVALID_INPUT");
        assertCode(() -> service.retry("trusted", ID, 0, new HashSet<>(Arrays.asList((String) null))), "INVALID_INPUT");
        verifyNoInteractions(execution);
    }

    @Test
    void validatesRetrySelectionAgainstTheOwnerScopedTaskNodes() {
        Map<String, DurableTaskView.NodeView> profilePlan = Map.of(
                "profile", failedNode(), "compose", failedNode());
        doReturn(taskV2("FAILED", null, profilePlan)).when(execution).query("trusted", ID);
        doReturn(taskV2("QUEUED", null, profilePlan)).when(execution)
                .retry("trusted", ID, 2, Set.of("profile"));

        service.retry("trusted", ID, 2, Set.of("profile"));
        assertCode(() -> service.retry("trusted", ID, 2, Set.of("evidence")), "INVALID_INPUT");

        Map<String, DurableTaskView.NodeView> evidencePlan = Map.of(
                "evidence", failedNode(), "compose", failedNode());
        doReturn(taskV2("FAILED", null, evidencePlan)).when(execution).query("trusted", ID);
        doReturn(taskV2("QUEUED", null, evidencePlan)).when(execution)
                .retry("trusted", ID, 2, Set.of());
        service.retry("trusted", ID, 2, Set.of());

        verify(execution).retry("trusted", ID, 2, Set.of("profile"));
        verify(execution).retry("trusted", ID, 2, Set.of());
        verify(execution, never()).retry("trusted", ID, 2, Set.of("evidence"));
    }

    @Test
    void returnsTypedSafeFinalPlanWithoutExecutionJson() {
        when(execution.query("trusted", ID)).thenReturn(task("COMPLETED", result()));
        var view = service.query("trusted", ID);
        assertThat(view.result().plan()).isEqualTo("Three day plan");
        assertThat(view.result().evidence().getFirst().documentId()).isEqualTo("doc-1");
        assertThat(view.nodes().get("profile").attempts()).isEqualTo(1);
        assertThat(view.nodes().get("profile").code()).isEqualTo("EXECUTION_FAILED");
        assertThatCode(() -> mapper.writeValueAsString(view)).doesNotThrowAnyException();
        assertThat(view.toString()).doesNotContain("password=secret", "resultJson", "lease", "owner");
    }

    @Test
    void refusesCorruptOrUnexpectedResultsInsteadOfReturningRawJson() {
        for (String json : new String[]{"{}", "null", result() + " {}", result().replace("\"plan\":", "\"rawProfile\":\"secret\",\"plan\":"),
                result().replace("Three day plan", ""), result().replace("doc-1", ""), result().replace("COMPLETED", "FAILED"), result().replace("Concurrency", "x".repeat(1201)),
                result().replace("\"plan\":", "\"plan\":\"old\",\"plan\":")}) {
            when(execution.query("trusted", ID)).thenReturn(task("COMPLETED", json));
            assertCode(() -> service.query("trusted", ID), "INVALID_EXECUTION_RESULT");
        }
        when(execution.query("trusted", ID)).thenReturn(task("COMPLETED", null));
        assertCode(() -> service.query("trusted", ID), "INVALID_EXECUTION_RESULT");
    }

    @Test
    void rejectsFinalResultThatDoesNotMatchCommittedNodeStates() {
        var pending = new DurableTaskView.NodeView("PENDING", 0, "PENDING");
        when(execution.query("trusted", ID)).thenReturn(new DurableTaskView(ID, "COMPLETED", 2, result(), null,
                Map.of("profile", pending, "evidence", pending, "compose", pending), "LEARNING_PLAN_PREVIEW", 1));
        assertCode(() -> service.query("trusted", ID), "INVALID_EXECUTION_RESULT");
    }

    @Test
    void cannotQueryRetryOrDeleteAnotherWorkflowThroughLearningApi() {
        when(execution.query("trusted", ID)).thenReturn(new DurableTaskView(ID, "FAILED", 2, null, null,
                nodes(), "OTHER", 1));
        assertCode(() -> service.query("trusted", ID), "EXECUTION_NOT_FOUND");
        assertCode(() -> service.retry("trusted", ID, 2, Set.of()), "EXECUTION_NOT_FOUND");
        assertCode(() -> service.delete("trusted", ID), "EXECUTION_NOT_FOUND");
        verify(execution, never()).retry(any(), any(), anyInt(), any());
        verify(execution, never()).delete(any(), any());
    }

    @Test
    void mapsConflictsAndStorageErrorsWithoutLeakingMessages() {
        when(execution.query("trusted", ID)).thenThrow(new IllegalArgumentException("EXECUTION_NOT_FOUND"));
        assertCode(() -> service.query("trusted", ID), "EXECUTION_NOT_FOUND");
        doThrow(new DataAccessResourceFailureException("password=secret")).when(execution).query("trusted", ID);
        assertCode(() -> service.query("trusted", ID), "EXECUTION_UNAVAILABLE");
        doThrow(new IllegalStateException("password=secret")).when(execution).query("trusted", ID);
        assertCode(() -> service.query("trusted", ID), "INTERNAL_ERROR");
        doReturn(task("FAILED", null)).when(execution).query("trusted", ID);
        when(execution.retry("trusted", ID, 2, Set.of())).thenThrow(new IllegalStateException("REVISION_CONFLICT"));
        assertCode(() -> service.retry("trusted", ID, 2, Set.of()), "REVISION_CONFLICT");
        when(execution.submit(anyString(), anyString(), anyInt(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("EXECUTION_DISABLED"));
        assertCode(() -> service.submit("trusted", "key", "Java", null), "EXECUTION_DISABLED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"IDEMPOTENCY_CONFLICT", "EXECUTION_DISABLED"})
    void mapsSubmissionReasons(String code) {
        doThrow(new IllegalArgumentException(code)).when(execution)
                .submit(anyString(), anyString(), anyInt(), anyString(), anyString());
        assertCode(() -> service.submit("trusted", "key", "Java", null), code);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REVISION_CONFLICT", "EXECUTION_NOT_RETRYABLE", "RETRY_ROOT_NOT_FAILED", "ATTEMPT_BUDGET_EXHAUSTED", "EXECUTION_DISABLED"})
    void mapsRetryReasons(String code) {
        doReturn(task("FAILED", null)).when(execution).query("trusted", ID);
        doThrow(new IllegalStateException(code)).when(execution).retry("trusted", ID, 2, Set.of());
        assertCode(() -> service.retry("trusted", ID, 2, Set.of()), code);
    }

    @Test
    void deletingRunningTaskIsAConflictAndNotCancellation() {
        doReturn(task("RUNNING", null)).when(execution).query("trusted", ID);
        doThrow(new IllegalStateException("EXECUTION_NOT_TERMINAL")).when(execution).delete("trusted", ID);
        assertCode(() -> service.delete("trusted", ID), "EXECUTION_NOT_TERMINAL");
    }

    @Test
    void returnsDegradedAndFailedTypedResultsWithoutPretendingSuccess() {
        String degraded = result().replace("COMPLETED", "DEGRADED")
                .replace("\"personalized\":true", "\"personalized\":false")
                .replace("\"degradations\":[]", "\"degradations\":[\"PROFILE_UNAVAILABLE\"]")
                .replace("\"profile\":{\"status\":\"SUCCEEDED\"", "\"profile\":{\"status\":\"FAILED\"");
        Map<String, DurableTaskView.NodeView> degradedNodes = new java.util.HashMap<>(nodes());
        degradedNodes.put("profile", new DurableTaskView.NodeView("FAILED", 1, "NODE_FAILED"));
        doReturn(new DurableTaskView(ID, "DEGRADED", 2, degraded, null, degradedNodes, "LEARNING_PLAN_PREVIEW", 1))
                .when(execution).query("trusted", ID);
        var view = service.query("trusted", ID);
        assertThat(view.result().plan()).isNotBlank();
        assertThat(view.result().personalized()).isFalse();
        assertThat(view.result().degradations()).containsExactly("PROFILE_UNAVAILABLE");
        String failed = result().replace("COMPLETED", "FAILED").replace("Three day plan", "")
                .replace("\"compose\":{\"status\":\"SUCCEEDED\"", "\"compose\":{\"status\":\"FAILED\"");
        Map<String, DurableTaskView.NodeView> failedNodes = new java.util.HashMap<>(nodes());
        failedNodes.put("compose", new DurableTaskView.NodeView("FAILED", 1, "NODE_FAILED"));
        doReturn(new DurableTaskView(ID, "FAILED", 2, failed, "NODE_FAILED", failedNodes, "LEARNING_PLAN_PREVIEW", 1))
                .when(execution).query("trusted", ID);
        assertThat(service.query("trusted", ID).result().plan()).isEmpty();
    }

    @Test
    void acceptsBoundarySizedSubmissionAndAutoRetryRoots() {
        doReturn(task("QUEUED", null)).when(execution).submit(anyString(), anyString(), anyInt(), anyString(), anyString());
        service.submit("trusted", "k".repeat(120), "x".repeat(200), "y".repeat(2000));
        doReturn(task("FAILED", null)).when(execution).query("trusted", ID);
        doReturn(task("QUEUED", null)).when(execution).retry("trusted", ID, 2, Set.of());
        service.retry("trusted", ID, 2, Set.of());
        verify(execution).retry("trusted", ID, 2, Set.of());
    }

    private void assertCode(ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOf(LearningPlanAccessException.class).hasMessage(code).hasNoCause();
    }

    private DurableTaskView task(String status, String json) {
        return new DurableTaskView(ID, status, 2, json, null, nodes(), "LEARNING_PLAN_PREVIEW", 1);
    }

    private DurableTaskView taskV2(
            String status,
            String json,
            Map<String, DurableTaskView.NodeView> nodes) {
        return new DurableTaskView(ID, status, 2, json, null, nodes,
                "LEARNING_PLAN_PREVIEW", 2);
    }

    private DurableTaskView.NodeView failedNode() {
        return new DurableTaskView.NodeView("FAILED", 1, "NODE_FAILED");
    }

    private Map<String, DurableTaskView.NodeView> nodes() {
        return Map.of("profile", new DurableTaskView.NodeView("SUCCEEDED", 1, "password=secret"),
                "evidence", new DurableTaskView.NodeView("SUCCEEDED", 1, "OK"),
                "compose", new DurableTaskView.NodeView("SUCCEEDED", 1, "OK"));
    }

    private String result() {
        return """
                {"status":"COMPLETED","plan":"Three day plan","personalized":true,"evidenceAvailable":true,
                 "evidence":[{"documentId":"doc-1","title":"Java","excerpt":"Concurrency"}],"degradations":[],
                 "nodes":{"profile":{"status":"SUCCEEDED","code":"OK"},
                          "evidence":{"status":"SUCCEEDED","code":"OK"},"compose":{"status":"SUCCEEDED","code":"OK"}}}
                """;
    }
}
