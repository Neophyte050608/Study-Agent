package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import java.time.Instant;

/** Persistable progress for one isolated indexing operation. */
public record BenchmarkCheckpoint(
        BenchmarkDatasetIdentity identity,
        String corpusSource,
        int totalChunks,
        int completedChunks,
        int batchSize,
        BenchmarkCheckpointStatus status,
        Instant updatedAt
) {
}
