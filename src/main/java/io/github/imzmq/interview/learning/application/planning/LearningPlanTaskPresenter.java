package io.github.imzmq.interview.learning.application.planning;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.agent.application.contract.execution.DagNodeStatus;
import io.github.imzmq.interview.agent.application.contract.execution.durable.DurableTaskView;
import io.github.imzmq.interview.learning.application.contract.LearningPlanAccessException;
import io.github.imzmq.interview.learning.application.contract.result.DurableLearningPlanView;
import io.github.imzmq.interview.learning.application.contract.result.LearningPlanEvidence;
import io.github.imzmq.interview.learning.application.contract.result.LearningPlanPreview;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static io.github.imzmq.interview.learning.application.contract.LearningPlanAccessException.Code.*;

/** Strict projection of persisted v1 output, not an unrestricted JSON passthrough. */
final class LearningPlanTaskPresenter {
    private static final Set<String> NODES = Set.of("profile", "evidence", "compose");
    private static final Set<String> TASK_STATES = Set.of("QUEUED", "RUNNING", "COMPLETED", "DEGRADED", "FAILED", "TIMED_OUT");
    private static final Set<String> SUCCESS = Set.of("COMPLETED", "DEGRADED");
    private static final Set<String> REASONS = Set.of("PROFILE_UNAVAILABLE", "PROFILE_EMPTY", "EVIDENCE_UNAVAILABLE", "EVIDENCE_EMPTY");
    private static final Set<String> SAFE_CODES = Set.of("OK", "PENDING", "RUNNING", "NODE_FAILED", "DEPENDENCY_FAILED",
            "OVERLOADED", "TIMED_OUT", "INTERRUPTED", "EXECUTION_FAILED", "OUTPUT_INVALID", "LEASE_LOST", "ATTEMPT_BUDGET_EXHAUSTED");
    private final ObjectMapper mapper;

    LearningPlanTaskPresenter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    void requireWorkflow(DurableTaskView task) {
        if (task == null) throw invalid();
        if (!"LEARNING_PLAN_PREVIEW".equals(task.workflowType()) || task.workflowVersion() != 1) {
            throw new LearningPlanAccessException(EXECUTION_NOT_FOUND);
        }
    }

    DurableLearningPlanView present(DurableTaskView task) {
        requireWorkflow(task);
        try {
            if (!TASK_STATES.contains(task.status()) || task.revision() < 0 || !task.nodes().keySet().equals(NODES)) throw invalid();
            Map<String, DurableLearningPlanView.NodeView> nodes = new LinkedHashMap<>();
            task.nodes().forEach((id, node) -> {
                DagNodeStatus.valueOf(node.status());
                if (node.attempts() < 0 || node.attempts() > 3) throw invalid();
                nodes.put(id, new DurableLearningPlanView.NodeView(node.status(), node.attempts(), safeCode(node.code())));
            });
            LearningPlanPreview result = task.resultJson() == null ? null : result(task.resultJson(), task.status());
            if (SUCCESS.contains(task.status()) && result == null) throw invalid();
            if (result != null) {
                for (String id : NODES) {
                    if (!result.nodes().get(id).status().name().equals(nodes.get(id).status())) throw invalid();
                }
            }
            return new DurableLearningPlanView(task.taskId(), task.status(), task.revision(), result, safeCode(task.errorCode()), nodes);
        } catch (RuntimeException e) {
            throw invalid();
        }
    }

    private LearningPlanPreview result(String json, String taskStatus) {
        try {
            if (json.getBytes(StandardCharsets.UTF_8).length > 131072) throw invalid();
            JsonNode tree = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(json);
            fields(tree, Set.of("status", "plan", "personalized", "evidenceAvailable", "evidence", "degradations", "nodes"));
            String status = text(tree.get("status"), 20);
            String plan = text(tree.get("plan"), 131072);
            if (!status.equals(taskStatus) || !Set.of("COMPLETED", "DEGRADED", "FAILED", "TIMED_OUT").contains(status)
                    || !tree.get("personalized").isBoolean() || !tree.get("evidenceAvailable").isBoolean()) throw invalid();
            boolean composed = SUCCESS.contains(status);
            if (composed ? plan.isBlank() : !plan.isEmpty()) throw invalid();
            var evidenceTree = tree.get("evidence");
            if (!evidenceTree.isArray() || evidenceTree.size() > 4) throw invalid();
            List<LearningPlanEvidence> evidence = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (var item : evidenceTree) {
                fields(item, Set.of("documentId", "title", "excerpt"));
                String id = text(item.get("documentId"), 200), excerpt = text(item.get("excerpt"), 1200);
                if (id.isBlank() || !ids.add(id) || excerpt.isBlank()) throw invalid();
                evidence.add(new LearningPlanEvidence(id, text(item.get("title"), 120), excerpt));
            }
            var reasonsTree = tree.get("degradations");
            if (!reasonsTree.isArray() || reasonsTree.size() > 4) throw invalid();
            List<String> reasons = new ArrayList<>();
            for (var item : reasonsTree) {
                String reason = text(item, 40);
                if (!REASONS.contains(reason) || reasons.contains(reason)) throw invalid();
                reasons.add(reason);
            }
            fields(tree.get("nodes"), NODES);
            Map<String, LearningPlanPreview.NodeSummary> nodes = new LinkedHashMap<>();
            for (String id : NODES) {
                var item = tree.get("nodes").get(id);
                fields(item, Set.of("status", "code"));
                DagNodeStatus state = DagNodeStatus.valueOf(text(item.get("status"), 20));
                if (state == DagNodeStatus.PENDING || state == DagNodeStatus.RUNNING) throw invalid();
                String code = item.get("code").isNull() ? null : text(item.get("code"), 120);
                nodes.put(id, new LearningPlanPreview.NodeSummary(state, safeCode(code)));
            }
            if (composed != (nodes.get("compose").status() == DagNodeStatus.SUCCEEDED)
                    || ("COMPLETED".equals(status) && !reasons.isEmpty())
                    || ("DEGRADED".equals(status) && reasons.isEmpty())
                    || tree.get("evidenceAvailable").booleanValue() != !evidence.isEmpty()) throw invalid();
            return new LearningPlanPreview(status, plan, tree.get("personalized").booleanValue(),
                    tree.get("evidenceAvailable").booleanValue(), evidence, reasons, nodes);
        } catch (Exception e) {
            throw invalid();
        }
    }

    private void fields(JsonNode tree, Set<String> allowed) {
        if (tree == null || !tree.isObject() || tree.size() != allowed.size()) throw invalid();
        Set<String> actual = new HashSet<>();
        tree.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(allowed)) throw invalid();
    }

    private String text(JsonNode tree, int max) {
        if (tree == null || !tree.isTextual() || tree.textValue().length() > max) throw invalid();
        return tree.textValue();
    }

    private String safeCode(String code) {
        return code == null ? null : SAFE_CODES.contains(code) ? code : "EXECUTION_FAILED";
    }

    private LearningPlanAccessException invalid() {
        return new LearningPlanAccessException(INVALID_EXECUTION_RESULT);
    }
}
