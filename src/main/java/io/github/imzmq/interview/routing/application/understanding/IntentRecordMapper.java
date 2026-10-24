package io.github.imzmq.interview.routing.application.understanding;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.imzmq.interview.modelrouting.application.contract.structuredoutput.StructuredOutputResult;
import io.github.imzmq.interview.modelrouting.application.contract.structuredoutput.StructuredOutputParser;
import io.github.imzmq.interview.modelrouting.application.contract.structuredoutput.StructuredOutputSchema;
import io.github.imzmq.interview.routing.application.contract.result.DecisionSource;
import io.github.imzmq.interview.routing.application.contract.result.IntentRecord;
import io.github.imzmq.interview.routing.application.contract.result.IntentTask;
import io.github.imzmq.interview.routing.application.contract.result.TurnRelation;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class IntentRecordMapper {

    private static final StructuredOutputSchema SCHEMA = StructuredOutputSchema.builder()
            .required("relation", "standaloneQuery", "tasks", "confidence", "reason")
            .type("relation", StructuredOutputSchema.JsonType.STRING, "NEW_TOPIC")
            .type("standaloneQuery", StructuredOutputSchema.JsonType.STRING, "")
            .type("tasks", StructuredOutputSchema.JsonType.ARRAY, List.of())
            .type("confidence", StructuredOutputSchema.JsonType.NUMBER, 0D)
            .type("reason", StructuredOutputSchema.JsonType.STRING, "")
            .allowed("relation", "NEW_TOPIC", "FOLLOW_UP", "REFINE", "CORRECT", "RETURN", "CONFIRM", "CHITCHAT")
            .build();

    private final StructuredOutputParser parser;

    public IntentRecordMapper(StructuredOutputParser parser) {
        this.parser = parser;
    }

    public IntentRecord map(String sessionId, String rawQuery, String rawResponse) {
        StructuredOutputResult<JsonNode> parsed = parser.parseTree(rawResponse, SCHEMA, null);
        if (!parsed.success()) {
            return IntentRecord.fallback(sessionId, rawQuery, parsed.failureReason());
        }
        try {
            JsonNode root = parsed.data();
            TurnRelation relation = TurnRelation.valueOf(root.path("relation").asText().trim().toUpperCase());
            double confidence = probability(root.get("confidence"), "confidence");
            List<IntentTask> tasks = mapFirstTask(root.path("tasks"));
            return new IntentRecord(
                    sessionId,
                    rawQuery,
                    relation,
                    root.path("standaloneQuery").asText(rawQuery),
                    tasks,
                    confidence,
                    root.path("reason").asText(""),
                    DecisionSource.LLM
            );
        } catch (RuntimeException ex) {
            return IntentRecord.fallback(sessionId, rawQuery, "intent_record_mapping:" + ex.getMessage());
        }
    }

    private List<IntentTask> mapFirstTask(JsonNode tasksNode) {
        if (!tasksNode.isArray() || tasksNode.isEmpty()) {
            return List.of();
        }
        JsonNode node = tasksNode.get(0);
        if (!node.isObject()) {
            throw new IllegalArgumentException("task must be an object");
        }
        String intentLeafId = node.path("intentLeafId").asText("").trim();
        if (intentLeafId.isEmpty()) {
            throw new IllegalArgumentException("intentLeafId is required");
        }
        Map<String, String> slots = new LinkedHashMap<>();
        JsonNode slotsNode = node.path("slots");
        if (!slotsNode.isMissingNode() && !slotsNode.isObject()) {
            throw new IllegalArgumentException("slots must be an object");
        }
        slotsNode.fields().forEachRemaining(entry -> slots.put(entry.getKey(), entry.getValue().asText("")));
        return List.of(new IntentTask(intentLeafId, slots, probability(node.get("score"), "task.score")));
    }

    private double probability(JsonNode node, String field) {
        if (node == null || !node.isNumber()) {
            throw new IllegalArgumentException(field + " must be numeric");
        }
        double value = node.asDouble();
        if (!Double.isFinite(value) || value < 0D || value > 1D) {
            throw new IllegalArgumentException(field + " must be between 0 and 1");
        }
        return value;
    }
}
