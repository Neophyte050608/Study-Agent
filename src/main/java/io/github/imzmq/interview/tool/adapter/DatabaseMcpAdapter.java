package io.github.imzmq.interview.tool.adapter;

import java.util.List;
import java.util.Map;

public interface DatabaseMcpAdapter {
    String namespace();

    List<String> capabilities();

    default boolean supports(String capability) {
        if (capability == null) {
            return false;
        }
        String normalized = capability.trim().toLowerCase();
        String namespacePrefix = namespace() == null ? "" : namespace().trim().toLowerCase();
        if (namespacePrefix.isBlank()) {
            return false;
        }
        return normalized.startsWith(namespacePrefix + ".");
    }

    Object invoke(String capability, Map<String, Object> params, Map<String, Object> context);
}

