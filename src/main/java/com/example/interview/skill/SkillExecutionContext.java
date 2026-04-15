package com.example.interview.skill;

import java.util.List;
import java.util.Map;

public record SkillExecutionContext(
        String traceId,
        String operator,
        Map<String, Object> input,
        SkillExecutionBudget budget
) {

    public Object value(String key) {
        return input == null ? null : input.get(key);
    }

    public String text(String key) {
        Object value = value(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    public boolean bool(String key) {
        Object value = value(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = value == null ? "" : String.valueOf(value).trim();
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text);
    }

    public int intValue(String key, int defaultValue) {
        Object value = value(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? defaultValue : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    public double doubleValue(String key, double defaultValue) {
        Object value = value(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? defaultValue : Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> map(String key) {
        Object value = value(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> list(String key) {
        Object value = value(key);
        return value instanceof List<?> list ? (List<T>) list : List.of();
    }
}
