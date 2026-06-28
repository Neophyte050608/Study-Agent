package io.github.imzmq.interview.tool.core;

import java.util.List;

public record ToolDefinition(
        String id,
        String name,
        String description,
        List<String> capabilities
) {

    public ToolDefinition {
        id = safeText(id);
        name = safeText(name).isBlank() ? id : safeText(name);
        description = safeText(description);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }

    private static String safeText(String value) {
        return value == null ? "" : value.trim();
    }
}
