package com.example.interview.agent.router;

import com.example.interview.agent.task.TaskRequest;
import com.example.interview.service.KnowledgeRetrievalMode;
import com.example.interview.service.LearningProfileAgent;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TaskHandlerSupport {

    private TaskHandlerSupport() {
    }

    public static String readText(Map<String, Object> payload, String key) {
        if (payload == null) {
            return "";
        }
        Object value = payload.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    public static Integer readInt(Map<String, Object> payload, String key) {
        if (payload == null) {
            return null;
        }
        Object raw = payload.get(key);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public static Map<String, Object> safePayload(Map<String, Object> payload) {
        return payload == null ? Map.of() : payload;
    }

    public static String resolveUserId(TaskRequest request, LearningProfileAgent learningProfileAgent) {
        String fromPayload = readText(request.payload(), "userId");
        if (!fromPayload.isBlank()) {
            return learningProfileAgent.normalizeUserId(fromPayload);
        }
        return learningProfileAgent.normalizeUserId(readText(request.context(), "userId"));
    }

    public static List<String> readTextList(Map<String, Object> payload, String key) {
        if (payload == null) {
            return List.of();
        }
        Object raw = payload.get(key);
        if (raw instanceof List<?> list) {
            List<String> data = new ArrayList<>();
            for (Object item : list) {
                if (item != null && !item.toString().isBlank()) {
                    data.add(item.toString().trim());
                }
            }
            return data;
        }
        return List.of();
    }

    public static Map<String, Object> enrichCodingPayload(TaskRequest request, LearningProfileAgent learningProfileAgent) {
        Map<String, Object> merged = new LinkedHashMap<>(safePayload(request.payload()));
        if (!merged.containsKey("userId")) {
            merged.put("userId", resolveUserId(request, learningProfileAgent));
        }
        if (request.payload() != null && request.payload().containsKey("type")) {
            merged.put("type", request.payload().get("type"));
        }
        return merged;
    }

    public static KnowledgeRetrievalMode resolveKnowledgeRetrievalMode(TaskRequest request) {
        return KnowledgeRetrievalMode.fromNullable(readText(request.context(), "retrievalMode"), null);
    }

    public static Instant parseTimestamp(String raw) {
        if (raw == null || raw.isBlank()) {
            return Instant.now();
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException ignored) {
            return Instant.now();
        }
    }
}
