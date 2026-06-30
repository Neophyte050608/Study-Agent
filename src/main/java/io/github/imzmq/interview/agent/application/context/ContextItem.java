package io.github.imzmq.interview.agent.application.context;

import java.util.Map;

public record ContextItem(
        String text,
        double score,
        String source,
        Map<String, String> metadata
) {
    public ContextItem {
        text = text == null ? "" : text.trim();
        source = source == null ? "" : source.trim();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public boolean isBlank() {
        return text.isBlank();
    }
}
