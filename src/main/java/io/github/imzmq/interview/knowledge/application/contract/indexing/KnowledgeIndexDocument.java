package io.github.imzmq.interview.knowledge.application.contract.indexing;

import java.util.Map;

/** Framework-neutral document exchanged with Knowledge indexing capabilities. */
public record KnowledgeIndexDocument(String id, String text, Map<String, Object> metadata) {
    public KnowledgeIndexDocument {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
