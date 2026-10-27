package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

/**
 * Resolves the stable corpus chunk identifier from a retrieved Spring AI document.
 *
 * <p>Imported benchmark corpora should write {@code chunk_id}. Existing Study-Agent
 * indexes use {@code child_id} or {@code doc_id}, so those keys remain compatible.
 * The Spring AI document id is the final fallback.</p>
 */
final class RetrievedChunkIdentityResolver {

    private static final List<String> METADATA_KEYS = List.of(
            "chunk_id",
            "chunkId",
            "child_id",
            "doc_id"
    );

    private RetrievedChunkIdentityResolver() {
    }

    static String resolve(Document document) {
        if (document == null) {
            return "";
        }
        Map<String, Object> metadata = document.getMetadata();
        if (metadata != null) {
            for (String key : METADATA_KEYS) {
                String value = normalized(metadata.get(key));
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return normalized(document.getId());
    }

    private static String normalized(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).trim();
        return "null".equalsIgnoreCase(text) ? "" : text;
    }
}
