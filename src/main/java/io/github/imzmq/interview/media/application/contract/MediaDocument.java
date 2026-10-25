package io.github.imzmq.interview.media.application.contract;

import java.util.Map;

/** Framework-neutral document view used by Media contracts. */
public record MediaDocument(String id, String text, Map<String, Object> metadata) {
    public MediaDocument {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
