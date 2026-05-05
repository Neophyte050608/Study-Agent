package io.github.imzmq.interview.interview.api.support;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public final class ControllerHelper {
    private ControllerHelper() {}

    public static Map<String, Object> extractParameterSnapshot(Object rawParameterSnapshot) {
        if (!(rawParameterSnapshot instanceof Map<?, ?> map)) {
            return Map.of();
        }
        return map.entrySet().stream().collect(Collectors.toMap(
                entry -> String.valueOf(entry.getKey()),
                Map.Entry::getValue,
                (left, right) -> right,
                LinkedHashMap::new
        ));
    }

    public static String stringifyValue(Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        String text = String.valueOf(rawValue).trim();
        return text.isEmpty() ? null : text;
    }

    public static Boolean parseBooleanFlag(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Boolean value) {
            return value;
        }
        String text = raw.toString().trim().toLowerCase();
        if ("true".equals(text) || "1".equals(text) || "on".equals(text) || "yes".equals(text)) {
            return true;
        }
        if ("false".equals(text) || "0".equals(text) || "off".equals(text) || "no".equals(text)) {
            return false;
        }
        return null;
    }
}
