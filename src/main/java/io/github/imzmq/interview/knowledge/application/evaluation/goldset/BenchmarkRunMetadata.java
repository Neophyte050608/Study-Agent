package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

/** Reproducibility metadata for one isolated comparison run. */
public record BenchmarkRunMetadata(
        String queryFingerprint,
        String collectionName,
        String schemaVersion,
        int topK,
        int queryCount,
        int corpusChunkCount
) {
}
