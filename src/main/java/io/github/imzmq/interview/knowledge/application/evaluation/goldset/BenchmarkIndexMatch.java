package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

/** One isolated vector-index match. */
public record BenchmarkIndexMatch(String chunkId, double score) {

    public BenchmarkIndexMatch {
        chunkId = chunkId == null ? "" : chunkId.trim();
        if (chunkId.isEmpty()) {
            throw new IllegalArgumentException("BENCHMARK_INDEX_MATCH_CHUNK_ID_INVALID");
        }
    }
}
