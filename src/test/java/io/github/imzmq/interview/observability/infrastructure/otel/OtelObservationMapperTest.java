package io.github.imzmq.interview.observability.infrastructure.otel;

import io.github.imzmq.interview.observability.application.AiObservationEvent;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OtelObservationMapperTest {

    @Test
    void mapsOnlySafeFieldsToOtelAttributes() {
        Map<String, Object> sourceAttributes = new LinkedHashMap<>();
        sourceAttributes.put("model", "glm-4");
        sourceAttributes.put("provider", "zhipu");
        sourceAttributes.put("latencyMs", 120);
        sourceAttributes.put("completionMs", 240);
        sourceAttributes.put("promptTokens", 10);
        sourceAttributes.put("completionTokens", 20);
        sourceAttributes.put("totalTokens", 30);
        sourceAttributes.put("estimatedCost", "0.003");
        sourceAttributes.put("docCount", 4);
        sourceAttributes.put("retrievalMode", "hybrid");
        sourceAttributes.put("taskType", "answer_review");
        sourceAttributes.put("routeSource", "agent_router");

        AiObservationEvent event = AiObservationEvent.ragNode(
                "trace-1",
                "node-1",
                "retrieval",
                "raw event name must not leak",
                "success",
                sourceAttributes
        );

        Map<String, String> attributes = new OtelObservationMapper().toAttributes(event);

        assertThat(attributes).containsExactlyEntriesOf(Map.ofEntries(
                Map.entry("ai.event_type", "rag.node"),
                Map.entry("ai.category", "retrieval"),
                Map.entry("ai.status", "success"),
                Map.entry("ai.model", "glm-4"),
                Map.entry("ai.provider", "zhipu"),
                Map.entry("ai.latency_ms", "120"),
                Map.entry("ai.prompt_tokens", "10"),
                Map.entry("ai.completion_tokens", "20"),
                Map.entry("ai.total_tokens", "30"),
                Map.entry("ai.estimated_cost", "0.003"),
                Map.entry("rag.doc_count", "4"),
                Map.entry("rag.retrieval_mode", "hybrid"),
                Map.entry("agent.task_type", "answer_review"),
                Map.entry("agent.route_source", "agent_router")
        ));
        assertThatThrownBy(() -> attributes.put("ai.extra", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void doesNotMapSensitiveFields() {
        Map<String, Object> sourceAttributes = new LinkedHashMap<>();
        sourceAttributes.put("userId", "user-1");
        sourceAttributes.put("sessionId", "session-1");
        sourceAttributes.put("taskId", "task-1");
        sourceAttributes.put("candidateId", "candidate-1");
        sourceAttributes.put("prompt", "sensitive prompt");
        sourceAttributes.put("completion", "sensitive completion");
        sourceAttributes.put("error", "sensitive stacktrace");
        sourceAttributes.put("model", "safe-model");

        AiObservationEvent event = new AiObservationEvent(
                "agent.task",
                "trace-1",
                "node-1",
                "routing",
                "raw event name must not leak",
                "error",
                null,
                sourceAttributes
        );

        Map<String, String> attributes = new OtelObservationMapper().toAttributes(event);

        assertThat(attributes)
                .containsEntry("ai.event_type", "agent.task")
                .containsEntry("ai.category", "routing")
                .containsEntry("ai.status", "error")
                .containsEntry("ai.model", "safe-model")
                .doesNotContainKeys(
                        "userId",
                        "sessionId",
                        "taskId",
                        "candidateId",
                        "prompt",
                        "completion",
                        "error",
                        "ai.user_id",
                        "ai.session_id",
                        "ai.task_id",
                        "ai.candidate_id",
                        "ai.prompt",
                        "ai.completion",
                        "ai.error"
                );
        assertThat(attributes.values()).doesNotContain(
                "user-1",
                "session-1",
                "task-1",
                "candidate-1",
                "sensitive prompt",
                "sensitive completion",
                "sensitive stacktrace"
        );
    }

    @Test
    void spanNameUsesControlledEventTypeAndCategoryInsteadOfRawName() {
        AiObservationEvent event = AiObservationEvent.ragNode(
                "trace-1",
                "node-1",
                "retrieval",
                "raw name with spaces and secrets",
                "success",
                Map.of()
        );

        assertThat(new OtelObservationMapper().spanName(event)).isEqualTo("ai.rag_node.retrieval");
    }

    @Test
    void spanNameSanitizesSpecialCharactersAndLimitsPartLength() {
        String longEventType = "rag.node with spaces\n/slash:colon\u0007" + "x".repeat(100);
        String longCategory = "retrieval mode/with:bad\nchars\u001F" + "y".repeat(100);
        AiObservationEvent event = new AiObservationEvent(
                longEventType,
                "trace-1",
                "node-1",
                longCategory,
                "raw event name with spaces / colon: secret",
                "success",
                null,
                Map.of()
        );

        String spanName = new OtelObservationMapper().spanName(event);

        assertThat(spanName).startsWith("ai.");
        assertThat(spanName).doesNotContain(" ", "\n", "/", ":", "\u0007", "\u001F", "raw event name", "secret");
        assertThat(spanName).doesNotContain("__");
        String[] parts = spanName.substring("ai.".length()).split("\\.");
        assertThat(parts).hasSize(2);
        assertThat(parts[0]).hasSizeLessThanOrEqualTo(64);
        assertThat(parts[1]).hasSizeLessThanOrEqualTo(64);
        assertThat(parts[0]).matches("[a-zA-Z0-9_-]+");
        assertThat(parts[1]).matches("[a-zA-Z0-9_-]+");
    }

    @Test
    void fallsBackWhenEventIsNullOrControlledNameIsBlank() {
        OtelObservationMapper mapper = new OtelObservationMapper();

        assertThat(mapper.toAttributes(null)).isEmpty();
        assertThat(mapper.spanName(null)).isEqualTo("ai.event");

        AiObservationEvent blankEvent = new AiObservationEvent(" ", "trace", "node", " ", "raw name", " ", null, Map.of());

        assertThat(mapper.toAttributes(blankEvent)).isEmpty();
        assertThat(mapper.spanName(blankEvent)).isEqualTo("ai.event");

        AiObservationEvent unsafeOnlyEvent = new AiObservationEvent(" : /\n\u0007", "trace", "node", " : /\n\u0007", "raw name", " ", null, Map.of());
        assertThat(mapper.spanName(unsafeOnlyEvent)).isEqualTo("ai.event");
    }

    @Test
    void skipsBlankAttributeValuesAndUsesCompletionMsWhenLatencyMsIsMissing() {
        Map<String, Object> sourceAttributes = new LinkedHashMap<>();
        sourceAttributes.put("model", " ");
        sourceAttributes.put("provider", "openai");
        sourceAttributes.put("completionMs", 345);
        sourceAttributes.put("routeSource", null);

        AiObservationEvent event = AiObservationEvent.ragNode(
                "trace-1",
                "node-1",
                "generation",
                "raw name",
                "success",
                sourceAttributes
        );

        assertThat(new OtelObservationMapper().toAttributes(event))
                .containsEntry("ai.latency_ms", "345")
                .containsEntry("ai.provider", "openai")
                .doesNotContainKey("ai.model")
                .doesNotContainKey("agent.route_source");
    }
}
