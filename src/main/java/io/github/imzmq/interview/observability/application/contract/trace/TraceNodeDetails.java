package io.github.imzmq.interview.observability.application.contract.trace;

import java.util.List;

/** Stable structured details attached to a trace node. */
public record TraceNodeDetails(
        Integer schemaVersion,
        String retrievalMode,
        String intentRoute,
        Boolean cacheHit,
        Integer retrievedDocCount,
        List<String> retrievedDocumentRefs,
        Integer webSearchCount,
        Integer imageSearchCount,
        String fallbackReason,
        String modelName,
        Long firstTokenMs,
        Long completionMs
) {
    public TraceNodeDetails {
        schemaVersion = schemaVersion == null || schemaVersion <= 0 ? 1 : schemaVersion;
        retrievalMode = retrievalMode == null || retrievalMode.isBlank() ? null : retrievalMode.trim();
        intentRoute = intentRoute == null || intentRoute.isBlank() ? null : intentRoute.trim();
        retrievedDocCount = retrievedDocCount == null ? null : Math.max(0, retrievedDocCount);
        retrievedDocumentRefs = retrievedDocumentRefs == null ? List.of() : List.copyOf(retrievedDocumentRefs);
        webSearchCount = webSearchCount == null ? null : Math.max(0, webSearchCount);
        imageSearchCount = imageSearchCount == null ? null : Math.max(0, imageSearchCount);
        fallbackReason = fallbackReason == null || fallbackReason.isBlank() ? null : fallbackReason.trim();
        modelName = modelName == null || modelName.isBlank() ? null : modelName.trim();
        firstTokenMs = firstTokenMs == null ? null : Math.max(0L, firstTokenMs);
        completionMs = completionMs == null ? null : Math.max(0L, completionMs);
    }
}
