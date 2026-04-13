package com.example.interview.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class TraceAttributeSanitizer {

    private static final int MAX_VALUE_LENGTH = 120;
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "scene",
            "sessionId",
            "taskId",
            "userId",
            "routeSource",
            "taskType",
            "domain",
            "model",
            "provider",
            "docCount",
            "imageCount",
            "firstTokenMs",
            "completionMs",
            "chunkCount",
            "imageEventCount",
            "fallback",
            "fallbackReason",
            "status",
            "retrievalMode"
    );

    public Map<String, Object> sanitize(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!ALLOWED_KEYS.contains(key) || value == null) {
                return;
            }
            String text = String.valueOf(value).trim();
            if (text.isEmpty()) {
                return;
            }
            result.put(key, text.length() > MAX_VALUE_LENGTH ? text.substring(0, MAX_VALUE_LENGTH) : text);
        });
        return result;
    }
}
