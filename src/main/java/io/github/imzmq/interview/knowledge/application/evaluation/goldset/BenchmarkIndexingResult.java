package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

/** Completion summary for an isolated indexing operation. */
public record BenchmarkIndexingResult(
        BenchmarkDatasetIdentity identity,
        BenchmarkCheckpointStatus status,
        int completedChunks,
        int totalChunks
) {
}
