package com.example.interview.controller.support;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class RequestPayloadMapper {

    public Map<String, Object> toObjectMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        return map.entrySet().stream().collect(Collectors.toMap(
                entry -> String.valueOf(entry.getKey()),
                Map.Entry::getValue,
                (left, right) -> right,
                LinkedHashMap::new
        ));
    }

    public String resolveTraceId(Map<String, Object> context) {
        if (context == null) {
            return UUID.randomUUID().toString();
        }
        Object traceId = context.get("traceId");
        if (traceId == null) {
            return UUID.randomUUID().toString();
        }
        String text = String.valueOf(traceId).trim();
        return text.isBlank() ? UUID.randomUUID().toString() : text;
    }

    public Map<String, Object> ensureTraceId(Map<String, Object> context, String traceId) {
        Map<String, Object> base = context == null ? new LinkedHashMap<>() : new LinkedHashMap<>(context);
        Object current = base.get("traceId");
        if (current == null || String.valueOf(current).trim().isBlank()) {
            base.put("traceId", traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId);
        }
        return base;
    }

    public Map<String, Object> ensureUserId(Map<String, Object> context, String userId) {
        Map<String, Object> base = context == null ? new LinkedHashMap<>() : new LinkedHashMap<>(context);
        if (!base.containsKey("userId")) {
            base.put("userId", userId);
        }
        return base;
    }
}
