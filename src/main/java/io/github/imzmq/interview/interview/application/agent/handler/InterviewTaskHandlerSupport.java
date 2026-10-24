package io.github.imzmq.interview.interview.application.agent.handler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class InterviewTaskHandlerSupport {

    private InterviewTaskHandlerSupport() {
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
}
