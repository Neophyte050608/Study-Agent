package io.github.imzmq.interview.observability.infrastructure.otel;

import io.github.imzmq.interview.observability.application.AiObservationEvent;

import java.util.LinkedHashMap;
import java.util.Map;

public class OtelObservationMapper {

    private static final String DEFAULT_SPAN_NAME = "ai.event";
    private static final int MAX_SPAN_NAME_PART_LENGTH = 64;

    public OtelObservationMapper() {
    }

    public String spanName(AiObservationEvent event) {
        if (event == null) {
            return DEFAULT_SPAN_NAME;
        }

        String eventType = sanitizeNamePart(event.eventType());
        String category = sanitizeNamePart(event.category());
        if (eventType.isEmpty() || category.isEmpty()) {
            return DEFAULT_SPAN_NAME;
        }
        return "ai." + eventType + "." + category;
    }

    public Map<String, String> toAttributes(AiObservationEvent event) {
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
        putIfHasText(attributes, "rag.doc_count", source.get("docCount"));
        putIfHasText(attributes, "rag.retrieval_mode", source.get("retrievalMode"));
        putIfHasText(attributes, "agent.task_type", source.get("taskType"));
        putIfHasText(attributes, "agent.route_source", source.get("routeSource"));
        putPlanningAttributes(attributes, source);

        if (attributes.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(attributes);
    }

    private static void putPlanningAttributes(Map<String, String> target, Map<String, Object> source) {
        putIfHasText(target, "agent.planning.planner_type", source.get("plannerType"));
        putIfHasText(target, "agent.planning.planner_version", source.get("plannerVersion"));
        putIfHasText(target, "agent.planning.scenario", source.get("scenario"));
        putIfHasText(target, "agent.planning.schema_version", source.get("schemaVersion"));
        putIfHasText(target, "agent.planning.node_count", source.get("nodeCount"));
        putIfHasText(target, "agent.planning.depth", source.get("depth"));
        putIfHasText(target, "agent.planning.capabilities", source.get("capabilities"));
        putIfHasText(target, "agent.planning.plan_digest", source.get("planDigest"));
        putIfHasText(target, "agent.planning.rule_digest", source.get("ruleDigest"));
        putIfHasText(target, "agent.planning.shadow_digest", source.get("shadowDigest"));
        putIfHasText(target, "agent.planning.same", source.get("same"));
        putIfHasText(target, "agent.planning.node_delta", source.get("nodeDelta"));
        putIfHasText(target, "agent.planning.duration_ms", source.get("durationMs"));
        putIfHasText(target, "agent.planning.error_code", source.get("errorCode"));
        putIfHasText(target, "agent.planning.fallback_target", source.get("fallbackTarget"));
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

        StringBuilder sanitized = new StringBuilder(value.length());
        boolean previousUnderscore = false;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            char replacement = safeSpanNameChar(current);
            if (replacement == '_') {
                if (!previousUnderscore) {
                    sanitized.append(replacement);
                    previousUnderscore = true;
                }
                continue;
            }

            sanitized.append(replacement);
            previousUnderscore = false;
        }

        String part = stripBoundaryUnderscores(sanitized.toString());
        if (part.length() > MAX_SPAN_NAME_PART_LENGTH) {
            part = stripBoundaryUnderscores(part.substring(0, MAX_SPAN_NAME_PART_LENGTH));
        }
        return part;
    }

    private static char safeSpanNameChar(char value) {
        if (value == '.') {
            return '_';
        }
        if (value == '_' || value == '-' || isAsciiLetterOrDigit(value)) {
            return value;
        }
        return '_';
    }

    private static boolean isAsciiLetterOrDigit(char value) {
        return value >= 'a' && value <= 'z'
                || value >= 'A' && value <= 'Z'
                || value >= '0' && value <= '9';
    }

    private static String stripBoundaryUnderscores(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '_') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '_') {
            end--;
        }
        return value.substring(start, end);
    }
}
