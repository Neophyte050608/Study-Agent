package io.github.imzmq.interview.observability.infrastructure.otel;

import io.github.imzmq.interview.observability.application.AiObservationEvent;

import java.util.LinkedHashMap;
import java.util.Map;

public final class OtelObservationMapper {

    private static final String DEFAULT_SPAN_NAME = "ai.event";

    private OtelObservationMapper() {
    }

    public static String spanName(AiObservationEvent event) {
        if (event == null) {
            return DEFAULT_SPAN_NAME;
        }

        String eventType = sanitizeNamePart(event.eventType());
        String category = sanitizeNamePart(event.category());
        if (eventType.isEmpty() && category.isEmpty()) {
            return DEFAULT_SPAN_NAME;
        }
        if (category.isEmpty()) {
            return "ai." + eventType;
        }
        if (eventType.isEmpty()) {
            return "ai." + category;
        }
        return "ai." + eventType + "." + category;
    }

    public static Map<String, String> toAttributes(AiObservationEvent event) {
        if (event == null) {
            return Map.of();
        }

        Map<String, String> attributes = new LinkedHashMap<>();
        putIfHasText(attributes, "ai.event_type", event.eventType());
        putIfHasText(attributes, "ai.category", event.category());
        putIfHasText(attributes, "ai.status", event.status());

        Map<String, Object> source = event.attributes();
        putIfHasText(attributes, "ai.model", source.get("model"));
        putIfHasText(attributes, "ai.provider", source.get("provider"));
        putLatency(attributes, source);
        putIfHasText(attributes, "ai.prompt_tokens", source.get("promptTokens"));
        putIfHasText(attributes, "ai.completion_tokens", source.get("completionTokens"));
        putIfHasText(attributes, "ai.total_tokens", source.get("totalTokens"));
        putIfHasText(attributes, "ai.estimated_cost", source.get("estimatedCost"));
        putIfHasText(attributes, "ai.rag.doc_count", source.get("docCount"));
        putIfHasText(attributes, "ai.rag.retrieval_mode", source.get("retrievalMode"));
        putIfHasText(attributes, "ai.agent.task_type", source.get("taskType"));
        putIfHasText(attributes, "ai.agent.route_source", source.get("routeSource"));

        if (attributes.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(attributes);
    }

    private static void putLatency(Map<String, String> target, Map<String, Object> source) {
        Object latencyMs = source.get("latencyMs");
        if (hasText(latencyMs)) {
            putIfHasText(target, "ai.latency_ms", latencyMs);
            return;
        }
        putIfHasText(target, "ai.latency_ms", source.get("completionMs"));
    }

    private static void putIfHasText(Map<String, String> target, String key, Object value) {
        if (!hasText(value)) {
            return;
        }
        target.put(key, value.toString().trim());
    }

    private static boolean hasText(Object value) {
        return value != null && !value.toString().isBlank();
    }

    private static String sanitizeNamePart(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().replace('.', '_');
    }
}
