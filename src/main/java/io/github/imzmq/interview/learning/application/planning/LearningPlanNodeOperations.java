package io.github.imzmq.interview.learning.application.planning;

import io.github.imzmq.interview.learning.application.contract.LearningProfileOperations;
import io.github.imzmq.interview.learning.application.contract.extension.LearningPlanEvidenceReader;
import io.github.imzmq.interview.learning.application.contract.extension.LearningPlanPreviewGenerator;
import io.github.imzmq.interview.learning.application.contract.result.LearningPlanEvidence;
import io.github.imzmq.interview.learning.application.contract.result.LearningPlanPreviewInput;
import io.github.imzmq.interview.learning.application.contract.result.TrainingProfileSnapshot;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Component
public final class LearningPlanNodeOperations {
    private final LearningProfileOperations profiles;
    private final LearningPlanEvidenceReader evidenceReader;
    private final LearningPlanPreviewGenerator generator;

    public LearningPlanNodeOperations(
            LearningProfileOperations profiles,
            LearningPlanEvidenceReader evidenceReader,
            LearningPlanPreviewGenerator generator) {
        this.profiles = Objects.requireNonNull(profiles);
        this.evidenceReader = Objects.requireNonNull(evidenceReader);
        this.generator = Objects.requireNonNull(generator);
    }

    public LearningPlanProfileMaterial readProfile(String owner) {
        TrainingProfileSnapshot snapshot = Objects.requireNonNull(
                profiles.snapshot(owner), "Missing profile");
        if (snapshot.totalEvents() == 0) {
            return new LearningPlanProfileMaterial("");
        }
        String text = "薄弱主题：" + snapshot.weakTopicRank().stream().limit(4).toList()
                + "\n近期趋势：" + snapshot.recentTrend()
                + "\n面试建议：" + snapshot.recommendedNextInterviewTopics().stream().limit(3).toList()
                + "\n刷题建议：" + snapshot.recommendedNextCodingTopics().stream().limit(4).toList();
        return new LearningPlanProfileMaterial(text.substring(0, Math.min(2400, text.length())));
    }

    public LearningPlanEvidenceMaterial readEvidence(String topic, int limit) {
        if (limit < 1 || limit > 5) {
            throw new IllegalArgumentException("Evidence limit must be within 1..5");
        }
        List<LearningPlanEvidence> evidence = List.copyOf(
                Objects.requireNonNull(evidenceReader.read(topic), "Missing evidence"));
        return new LearningPlanEvidenceMaterial(evidence.subList(0, Math.min(limit, evidence.size())));
    }

    public LearningPlanComposeMaterial compose(
            String topic,
            String request,
            Optional<LearningPlanProfileMaterial> profile,
            Optional<LearningPlanEvidenceMaterial> evidence,
            List<String> degradations) {
        LearningPlanPreviewInput input = new LearningPlanPreviewInput(
                topic,
                request,
                profile.map(LearningPlanProfileMaterial::text).orElse(""),
                evidence.map(LearningPlanEvidenceMaterial::evidence).orElse(List.of()),
                List.copyOf(degradations));
        return new LearningPlanComposeMaterial(generator.generate(input));
    }
}
