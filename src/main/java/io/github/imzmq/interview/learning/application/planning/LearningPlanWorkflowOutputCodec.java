package io.github.imzmq.interview.learning.application.planning;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.agent.application.contract.execution.DagExecutionResult;
import io.github.imzmq.interview.agent.application.contract.execution.durable.DurableWorkflow;
import io.github.imzmq.interview.learning.application.contract.result.LearningPlanEvidence;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Component
final class LearningPlanWorkflowOutputCodec {
    private final LearningPlanPreviewService preview;
    private final ObjectMapper mapper;

    LearningPlanWorkflowOutputCodec(
            LearningPlanPreviewService preview,
            ObjectMapper mapper) {
        this.preview = Objects.requireNonNull(preview);
        this.mapper = Objects.requireNonNull(mapper);
    }

    String encodeOutput(String nodeId, Object output) {
        try {
            String json = mapper.writeValueAsString(output);
            decodeOutput(nodeId, json);
            return json;
        } catch (Exception exception) {
            throw new IllegalArgumentException("INVALID_PREVIEW_OUTPUT");
        }
    }

    Object decodeOutput(String nodeId, String outputJson) {
        try {
            var tree = mapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .readTree(outputJson);
            if (Set.of("profile", "compose").contains(nodeId)) {
                if (tree == null || !tree.isTextual()) {
                    throw new IllegalArgumentException();
                }
                String value = tree.textValue();
                if ((nodeId.equals("profile") && value.length() > 2400)
                        || (nodeId.equals("compose") && value.isBlank())) {
                    throw new IllegalArgumentException();
                }
                return value;
            }
            if (!"evidence".equals(nodeId) || tree == null
                    || !tree.isArray() || tree.size() > 4) {
                throw new IllegalArgumentException();
            }
            List<LearningPlanEvidence> result = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (var item : tree) {
                if (!item.isObject() || item.size() != 3 || !item.path("documentId").isTextual()
                        || !item.path("title").isTextual() || !item.path("excerpt").isTextual()) {
                    throw new IllegalArgumentException();
                }
                var evidence = mapper.treeToValue(item, LearningPlanEvidence.class);
                if (evidence.documentId().isBlank() || !ids.add(evidence.documentId())
                        || evidence.title().length() > 120 || evidence.excerpt().isBlank()
                        || evidence.excerpt().length() > 1200) {
                    throw new IllegalArgumentException();
                }
                result.add(evidence);
            }
            return List.copyOf(result);
        } catch (Exception exception) {
            throw new IllegalArgumentException("INVALID_PREVIEW_OUTPUT");
        }
    }

    DurableWorkflow.WorkflowOutcome outcome(DagExecutionResult result) {
        var view = preview.present(result);
        try {
            return new DurableWorkflow.WorkflowOutcome(
                    view.status(), mapper.writeValueAsString(view));
        } catch (Exception exception) {
            throw new IllegalArgumentException("INVALID_PREVIEW_RESULT");
        }
    }
}
