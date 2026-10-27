package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import java.util.List;

/** Input for a resumable isolated indexing operation. */
public record BenchmarkIndexCommand(
        BenchmarkDatasetIdentity identity,
        String corpusSource,
        List<GoldSetChunk> chunks,
        int batchSize
) {
    public BenchmarkIndexCommand {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }
}
