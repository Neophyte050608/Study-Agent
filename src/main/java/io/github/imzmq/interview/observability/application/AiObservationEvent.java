package io.github.imzmq.interview.observability.application;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record AiObservationEvent(
        String eventType,
        String traceId,
        String nodeId,
        String category,
        String name,
        String status,
        Instant eventTime,
        Map<String, Object> attributes
) {

    public AiObservationEvent {
        eventType = normalize(eventType);
        traceId = normalize(traceId);
        nodeId = normalize(nodeId);
        category = normalize(category);
        name = normalize(name);
        status = normalize(status);
        eventTime = eventTime == null ? Instant.now() : eventTime;
        attributes = copyAttributes(attributes);
    }

    public static AiObservationEvent ragNode(String traceId,
                                             String nodeId,
                                             String category,
                                             String name,
                                             String status,
                                             Map<String, Object> attributes) {
        return new AiObservationEvent(
                "rag.node",
                traceId,
                nodeId,
                category,
                name,
                status,
                Instant.now(),
                attributes
        );
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    private static Map<String, Object> copyAttributes(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> copied = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            copied.put(entry.getKey(), entry.getValue());
        }

        if (copied.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(copied);
    }
}
