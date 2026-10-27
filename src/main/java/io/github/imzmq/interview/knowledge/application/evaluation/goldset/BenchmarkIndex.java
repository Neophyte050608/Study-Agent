package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import java.util.List;

/** Isolated vector-index boundary used by retrieval benchmarks. */
public interface BenchmarkIndex {

    void upsertBatch(BenchmarkDatasetIdentity identity, List<GoldSetChunk> chunks);

    List<BenchmarkIndexMatch> search(BenchmarkDatasetIdentity identity, String query, int topK);

    long count(BenchmarkDatasetIdentity identity);

    void deleteDataset(String datasetId);
}
