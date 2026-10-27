package io.github.imzmq.interview.learning.application.planning;

import io.github.imzmq.interview.agent.application.contract.execution.*;
import io.github.imzmq.interview.learning.application.contract.LearningProfileOperations;
import io.github.imzmq.interview.learning.application.contract.extension.LearningPlanEvidenceReader;
import io.github.imzmq.interview.learning.application.contract.extension.LearningPlanPreviewGenerator;
import io.github.imzmq.interview.learning.application.contract.result.*;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

@Service
public final class LearningPlanPreviewService {
    private final DagExecutionOperations executor;
    private final LearningProfileOperations profiles;
    private final LearningPlanEvidenceReader evidenceReader;
    private final LearningPlanPreviewGenerator generator;

    public LearningPlanPreviewService(DagExecutionOperations executor, LearningProfileOperations profiles,
                                       LearningPlanEvidenceReader evidenceReader, LearningPlanPreviewGenerator generator) {
        this.executor = executor; this.profiles = profiles; this.evidenceReader = evidenceReader; this.generator = generator;
    }

    public LearningPlanPreview preview(String userId, String topic, String request) {
        String owner = required(userId, 200, "Execution user");
        String subject = required(topic, 200, "Topic");
        String instruction = request == null ? "" : request.trim();
        if (instruction.length() > 2000) throw new IllegalArgumentException("Request exceeds 2000 characters");
        return present(executor.execute(nodes(owner, subject, instruction), Duration.ofSeconds(75)));
    }

    List<DagNode> nodes(String owner, String subject, String instruction) {
        required(owner, 200, "Execution user"); required(subject, 200, "Topic");
        if (instruction == null || instruction.length() > 2000) throw new IllegalArgumentException("Invalid request");
        return List.of(
                new DagNode("profile", Set.of(), true, inputs -> profileContext(profiles.snapshot(owner))),
                new DagNode("evidence", Set.of(), true, inputs -> List.copyOf(evidenceReader.read(subject))),
                new DagNode("compose", Set.of("profile", "evidence"), false, inputs -> {
                    Materials materials = materials(inputs);
                    String plan = generator.generate(new LearningPlanPreviewInput(subject, instruction, materials.profile(),
                            materials.evidence(), materials.degradations()));
                    if (plan == null || plan.isBlank()) throw new IllegalStateException("Empty generated plan");
                    return plan;
                }));
    }

    LearningPlanPreview present(DagExecutionResult result) {
        Materials materials = materials(result.nodes());
        DagNodeResult compose = result.nodes().get("compose");
        Map<String, LearningPlanPreview.NodeSummary> states = new LinkedHashMap<>();
        result.nodes().forEach((id, node) -> states.put(id, new LearningPlanPreview.NodeSummary(node.status(), node.code())));
        boolean composed = compose.status() == DagNodeStatus.SUCCEEDED
                && ("COMPLETED".equals(result.code()) || "FAILED".equals(result.code()));
        String status = composed ? (materials.degradations().isEmpty() ? "COMPLETED" : "DEGRADED")
                : ("FAILED".equals(result.code()) ? "FAILED" : result.code());
        return new LearningPlanPreview(status, composed ? (String) compose.output() : "",
                !materials.profile().isBlank(), !materials.evidence().isEmpty(), materials.evidence(),
                materials.degradations(), states);
    }

    private Materials materials(Map<String, DagNodeResult> nodes) {
        DagNodeResult profile = nodes.get("profile"), evidence = nodes.get("evidence");
        List<String> reasons = new ArrayList<>();
        String profileText = profile.status() == DagNodeStatus.SUCCEEDED ? (String) profile.output() : "";
        if (profile.status() != DagNodeStatus.SUCCEEDED) reasons.add("PROFILE_UNAVAILABLE");
        else if (profileText.isBlank()) reasons.add("PROFILE_EMPTY");
        List<LearningPlanEvidence> references = List.of();
        if (evidence.status() == DagNodeStatus.SUCCEEDED) {
            @SuppressWarnings("unchecked")
            List<LearningPlanEvidence> output = (List<LearningPlanEvidence>) evidence.output();
            references = output;
            if (references.isEmpty()) reasons.add("EVIDENCE_EMPTY");
        } else reasons.add("EVIDENCE_UNAVAILABLE");
        return new Materials(profileText, references, List.copyOf(reasons));
    }

    private String profileContext(TrainingProfileSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "Missing profile");
        if (snapshot.totalEvents() == 0) return "";
        String text = "薄弱主题：" + snapshot.weakTopicRank().stream().limit(4).toList()
                + "\n近期趋势：" + snapshot.recentTrend()
                + "\n面试建议：" + snapshot.recommendedNextInterviewTopics().stream().limit(3).toList()
                + "\n刷题建议：" + snapshot.recommendedNextCodingTopics().stream().limit(4).toList();
        return text.substring(0, Math.min(2400, text.length()));
    }

    private String required(String value, int maximum, String name) {
        if (value == null || value.isBlank() || value.trim().length() > maximum) {
            throw new IllegalArgumentException(name + " is required and must be at most " + maximum + " characters");
        }
        return value.trim();
    }

    private record Materials(String profile, List<LearningPlanEvidence> evidence, List<String> degradations) { }
}
