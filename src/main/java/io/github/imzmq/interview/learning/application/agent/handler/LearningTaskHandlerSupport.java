package io.github.imzmq.interview.learning.application.agent.handler;

import io.github.imzmq.interview.agent.application.contract.task.TaskRequest;
import io.github.imzmq.interview.learning.application.LearningProfileAgent;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class LearningTaskHandlerSupport {

    private LearningTaskHandlerSupport() {
    }

    static String readText(Map<String, Object> payload, String key) {
        if (payload == null) {
            return "";
        }
        Object value = payload.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    static Integer readInt(Map<String, Object> payload, String key) {
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

    static Map<String, Object> safePayload(Map<String, Object> payload) {
        return payload == null ? Map.of() : payload;
    }

    static String resolveUserId(TaskRequest request, LearningProfileAgent learningProfileAgent) {
        String fromPayload = readText(request.payload(), "userId");
        if (!fromPayload.isBlank()) {
            return learningProfileAgent.normalizeUserId(fromPayload);
        }
        return learningProfileAgent.normalizeUserId(readText(request.context(), "userId"));
    }

    static List<String> readTextList(Map<String, Object> payload, String key) {
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

    static Instant parseTimestamp(String raw) {
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
