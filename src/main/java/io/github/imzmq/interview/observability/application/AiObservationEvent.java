package io.github.imzmq.interview.observability.application;

import java.time.Instant;
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
        attributes = attributes == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(attributes));
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
}
