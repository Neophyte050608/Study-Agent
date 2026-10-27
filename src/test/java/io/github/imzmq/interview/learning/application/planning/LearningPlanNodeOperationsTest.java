package io.github.imzmq.interview.learning.application.planning;

import io.github.imzmq.interview.learning.application.contract.LearningProfileOperations;
import io.github.imzmq.interview.learning.application.contract.result.LearningPlanEvidence;
import io.github.imzmq.interview.learning.application.contract.result.LearningPlanPreviewInput;
import io.github.imzmq.interview.learning.application.contract.result.TrainingProfileSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LearningPlanNodeOperationsTest {

    @Test
    void formatsTheExistingProfileContextAndKeepsTheEmptyProfileSemantic() {
        LearningProfileOperations profiles = mock(LearningProfileOperations.class);
        when(profiles.snapshot("owner")).thenReturn(new TrainingProfileSnapshot(
                List.of(Map.of("topic", "Java", "weakScore", .8)),
                List.of(),
                "UP",
                List.of("Java", "System Design"),
                List.of("Arrays", "Graphs"),
                3,
                "now"));
        LearningPlanNodeOperations operations = new LearningPlanNodeOperations(
                profiles, topic -> List.of(), input -> "plan");

        LearningPlanProfileMaterial material = operations.readProfile("owner");

        assertThat(material.text())
                .contains("薄弱主题：", "Java", "近期趋势：UP", "面试建议：", "刷题建议：")
                .hasSizeLessThanOrEqualTo(2400);
        verify(profiles).snapshot("owner");

        when(profiles.snapshot("empty")).thenReturn(new TrainingProfileSnapshot(
                List.of(), List.of(), "", List.of(), List.of(), 0, ""));
        assertThat(operations.readProfile("empty").text()).isEmpty();
    }

    @Test
    void copiesAndLimitsEvidenceWithoutMutatingTheReaderOutput() {
        List<LearningPlanEvidence> source = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            source.add(new LearningPlanEvidence("doc-" + index, "title", "excerpt"));
        }
        LearningPlanNodeOperations operations = new LearningPlanNodeOperations(
                mock(LearningProfileOperations.class), topic -> source, input -> "plan");

        LearningPlanEvidenceMaterial material = operations.readEvidence("Java", 4);
        source.clear();

        assertThat(material.evidence()).hasSize(4)
                .extracting(LearningPlanEvidence::documentId)
                .containsExactly("doc-0", "doc-1", "doc-2", "doc-3");
        assertThatThrownBy(() -> material.evidence().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> operations.readEvidence("Java", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> operations.readEvidence("Java", 6))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void composesUsingTheExistingInputContractAndRejectsBlankOutput() {
        AtomicReference<LearningPlanPreviewInput> captured = new AtomicReference<>();
        LearningPlanEvidence evidence = new LearningPlanEvidence("doc", "title", "excerpt");
        LearningPlanNodeOperations operations = new LearningPlanNodeOperations(
                mock(LearningProfileOperations.class), topic -> List.of(), input -> {
                    captured.set(input);
                    return "three day plan";
                });

        LearningPlanComposeMaterial material = operations.compose(
                "Java",
                "prepare",
                Optional.of(new LearningPlanProfileMaterial("profile")),
                Optional.of(new LearningPlanEvidenceMaterial(List.of(evidence))),
                List.of("PROFILE_STALE"));

        assertThat(material.plan()).isEqualTo("three day plan");
        assertThat(captured.get()).isEqualTo(new LearningPlanPreviewInput(
                "Java", "prepare", "profile", List.of(evidence), List.of("PROFILE_STALE")));

        LearningPlanNodeOperations blank = new LearningPlanNodeOperations(
                mock(LearningProfileOperations.class), topic -> List.of(), input -> "  ");
        assertThatThrownBy(() -> blank.compose(
                "Java", "", Optional.empty(), Optional.empty(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("EMPTY_PLAN");
    }
}
