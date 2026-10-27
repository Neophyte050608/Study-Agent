package io.github.imzmq.interview.learning.application.planning;

import io.github.imzmq.interview.agent.application.execution.FixedDagExecutor;
import io.github.imzmq.interview.agent.application.contract.execution.DagNodeStatus;
import io.github.imzmq.interview.learning.application.contract.LearningProfileOperations;
import io.github.imzmq.interview.learning.application.contract.extension.*;
import io.github.imzmq.interview.learning.application.contract.result.*;
import io.github.imzmq.interview.learning.application.agent.handler.LearningPlanPreviewTaskHandler;
import io.github.imzmq.interview.agent.application.contract.task.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LearningPlanPreviewServiceTest {
    private TrainingProfileSnapshot profile() {
        return new TrainingProfileSnapshot(List.of(Map.of("topic", "Java", "weakScore", .8)), List.of(),
                "UP", List.of("Java"), List.of(), 3, "now");
    }

    @Test
    void usesProvidedMaterialsAndReturnsOnlySafeNodeStates() {
        var profiles = mock(LearningProfileOperations.class);
        when(profiles.snapshot("owner")).thenReturn(profile());
        var materials = List.of(new LearningPlanEvidence("doc-1", "Java notes", "excerpt"));
        AtomicReference<LearningPlanPreviewInput> captured = new AtomicReference<>();
        try (var executor = new FixedDagExecutor()) {
            var service = new LearningPlanPreviewService(executor, profiles, topic -> materials,
                    input -> { captured.set(input); return "plan"; });
            var result = service.preview("owner", "Java", "prepare interview");
            assertEquals("COMPLETED", result.status()); assertEquals("plan", result.plan());
            assertTrue(result.personalized()); assertTrue(result.evidenceAvailable());
            assertEquals(materials, result.evidence()); assertEquals(List.of(), result.degradations());
            assertEquals(DagNodeStatus.SUCCEEDED, result.nodes().get("compose").status());
            assertEquals(materials, captured.get().evidence());
            assertTrue(captured.get().profileContext().contains("Java"));
            verify(profiles).snapshot("owner"); verifyNoMoreInteractions(profiles);
        }
    }

    @Test
    void honestlyDegradesEveryReadFailureCombination() {
        for (int mask = 1; mask <= 3; mask++) {
            final int failures = mask;
            var profiles = mock(LearningProfileOperations.class);
            if ((mask & 1) != 0) when(profiles.snapshot("owner")).thenThrow(new IllegalStateException("private"));
            else when(profiles.snapshot("owner")).thenReturn(profile());
            try (var executor = new FixedDagExecutor()) {
                var service = new LearningPlanPreviewService(executor, profiles, topic -> {
                    if ((failures & 2) != 0) throw new IllegalStateException("private");
                    return List.of(new LearningPlanEvidence("doc", "title", "text"));
                }, input -> "plan");
                var result = service.preview("owner", "Java", "");
                assertEquals("DEGRADED", result.status());
                assertEquals((mask & 1) == 0, result.personalized());
                assertEquals((mask & 2) == 0, result.evidenceAvailable());
                if ((mask & 1) != 0) assertTrue(result.degradations().contains("PROFILE_UNAVAILABLE"));
                if ((mask & 2) != 0) { assertTrue(result.degradations().contains("EVIDENCE_UNAVAILABLE")); assertTrue(result.evidence().isEmpty()); }
                assertFalse(result.toString().contains("private"));
                verify(profiles).snapshot("owner"); verifyNoMoreInteractions(profiles);
            }
        }
    }

    @Test
    void emptyDataIsNotConfusedWithInfrastructureFailure() {
        var profiles = mock(LearningProfileOperations.class);
        when(profiles.snapshot("owner")).thenReturn(new TrainingProfileSnapshot(List.of(), List.of(), "", List.of(), List.of(), 0, ""));
        try (var executor = new FixedDagExecutor()) {
            var service = new LearningPlanPreviewService(executor, profiles, topic -> List.of(), input -> "generic plan");
            var result = service.preview("owner", "Java", "");
            assertEquals(List.of("PROFILE_EMPTY", "EVIDENCE_EMPTY"), result.degradations());
            assertFalse(result.personalized()); assertFalse(result.evidenceAvailable());
        }
    }

    @Test
    void generatorFailureOrEmptyAnswerIsNotSuccessfulPreview() {
        for (boolean empty : List.of(true, false)) {
            var profiles = mock(LearningProfileOperations.class);
            when(profiles.snapshot("owner")).thenReturn(profile());
            try (var executor = new FixedDagExecutor()) {
                var service = new LearningPlanPreviewService(executor, profiles, topic -> List.of(), input -> {
                    if (empty) return " "; throw new IllegalStateException("secret upstream");
                });
                var result = service.preview("owner", "Java", "");
                assertEquals("FAILED", result.status()); assertEquals("", result.plan());
                assertEquals(DagNodeStatus.FAILED, result.nodes().get("compose").status());
                assertFalse(result.toString().contains("secret"));
            }
        }
    }

    @Test
    void handlerRejectsMissingTrustedUserAndPayloadCannotOverrideContextUser() {
        var profiles = mock(LearningProfileOperations.class);
        when(profiles.snapshot("owner")).thenReturn(profile());
        try (var executor = new FixedDagExecutor()) {
            var service = new LearningPlanPreviewService(executor, profiles, topic -> List.of(), input -> "plan");
            var handler = new LearningPlanPreviewTaskHandler(service);
            var response = (TaskResponse) handler.handle(new TaskRequest(TaskType.LEARNING_PLAN_PREVIEW,
                    Map.of("topic", "Java", "userId", "attacker", "useMcp", true), Map.of("userId", "owner")));
            assertTrue(response.success()); verify(profiles).snapshot("owner");
            verify(profiles, never()).snapshot("attacker");
            assertThrows(IllegalArgumentException.class, () -> handler.handle(new TaskRequest(TaskType.LEARNING_PLAN_PREVIEW,
                    Map.of("topic", "Java", "userId", "attacker"), Map.of())));
            assertThrows(IllegalArgumentException.class, () -> service.preview("owner", " ", "query"));
            assertThrows(IllegalArgumentException.class, () -> service.preview("owner", "x".repeat(201), "query"));
        }
    }
}
