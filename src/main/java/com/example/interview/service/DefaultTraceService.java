package com.example.interview.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
public class DefaultTraceService implements TraceService {

    private final RAGObservabilityService ragObservabilityService;
    private final TraceAttributeSanitizer traceAttributeSanitizer;

    public DefaultTraceService(RAGObservabilityService ragObservabilityService,
                               TraceAttributeSanitizer traceAttributeSanitizer) {
        this.ragObservabilityService = ragObservabilityService;
        this.traceAttributeSanitizer = traceAttributeSanitizer;
    }

    @Override
    public TraceNodeHandle startRoot(String traceId, TraceNodeDefinition definition, Map<String, Object> attributes) {
        String nodeId = UUID.randomUUID().toString();
        ragObservabilityService.startNode(traceId, nodeId, null, definition.nodeType(), definition.nodeName());
        return new TraceNodeHandle(traceId, nodeId, null, definition);
    }

    @Override
    public TraceNodeHandle startChild(String traceId, String parentNodeId, TraceNodeDefinition definition, Map<String, Object> attributes) {
        String nodeId = UUID.randomUUID().toString();
        ragObservabilityService.startNode(traceId, nodeId, parentNodeId, definition.nodeType(), definition.nodeName());
        return new TraceNodeHandle(traceId, nodeId, parentNodeId, definition);
    }

    @Override
    public void success(TraceNodeHandle handle, Map<String, Object> result) {
        ragObservabilityService.endNode(
                handle.traceId(),
                handle.nodeId(),
                summarize(Map.of()),
                summarize(result),
                null,
                null,
                toNodeDetails(result)
        );
    }

    @Override
    public void fail(TraceNodeHandle handle, String errorMessage, Map<String, Object> result) {
        ragObservabilityService.endNode(
                handle.traceId(),
                handle.nodeId(),
                summarize(Map.of()),
                summarize(result),
                errorMessage,
                null,
                toNodeDetails(result)
        );
    }

    private String summarize(Map<String, Object> payload) {
        Map<String, Object> sanitized = traceAttributeSanitizer.sanitize(payload);
        return sanitized.isEmpty() ? "" : sanitized.toString();
    }

    private RAGObservabilityService.NodeDetails toNodeDetails(Map<String, Object> payload) {
        Map<String, Object> sanitized = traceAttributeSanitizer.sanitize(payload);
        if (sanitized.isEmpty()) {
            return null;
        }
        Long firstTokenMs = toLong(sanitized.get("firstTokenMs"));
        Long completionMs = toLong(sanitized.get("completionMs"));
        String retrievalMode = asString(sanitized.get("retrievalMode"));
        String fallbackReason = asString(sanitized.get("fallbackReason"));
        String modelName = asString(sanitized.get("model"));
        Integer retrievedDocCount = toInteger(sanitized.get("docCount"));
        Integer imageSearchCount = firstNonNull(toInteger(sanitized.get("imageCount")), toInteger(sanitized.get("imageEventCount")));
        if (firstTokenMs == null
                && completionMs == null
                && retrievalMode == null
                && fallbackReason == null
                && modelName == null
                && retrievedDocCount == null
                && imageSearchCount == null) {
            return null;
        }
        return new RAGObservabilityService.NodeDetails(
                1,
                retrievalMode,
                null,
                null,
                retrievedDocCount,
                java.util.List.of(),
                null,
                imageSearchCount,
                fallbackReason,
                modelName,
                firstTokenMs,
                completionMs
        );
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        if (values == null) {
            return null;
        }
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
