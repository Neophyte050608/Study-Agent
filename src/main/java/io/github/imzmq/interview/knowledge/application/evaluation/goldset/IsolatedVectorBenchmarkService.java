package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import java.util.List;

/** Compares a completed isolated vector index with the local BM25 baseline. */
public final class IsolatedVectorBenchmarkService {

    private final BenchmarkIndex index;
    private final BenchmarkCheckpointStore checkpointStore;
    private final IsolatedVectorEvaluator vectorEvaluator;
    private final GoldSetBm25Evaluator bm25Evaluator;
    private final GoldSetRunComparator runComparator;

    public IsolatedVectorBenchmarkService(
            BenchmarkIndex index,
            BenchmarkCheckpointStore checkpointStore,
            IsolatedVectorEvaluator vectorEvaluator,
            GoldSetBm25Evaluator bm25Evaluator,
            GoldSetRunComparator runComparator
    ) {
        this.index = index;
        this.checkpointStore = checkpointStore;
        this.vectorEvaluator = vectorEvaluator;
        this.bm25Evaluator = bm25Evaluator;
        this.runComparator = runComparator;
    }

    public IsolatedBenchmarkReport compare(
            BenchmarkDatasetIdentity identity,
            List<GoldSetCase> cases,
            List<GoldSetChunk> corpus,
            BenchmarkRunMetadata metadata
    ) {
        requireReadyIndex(identity, corpus);
        GoldSetEvaluationRun vectorRun = vectorEvaluator.run(identity, cases);
        GoldSetEvaluationRun bm25Run = bm25Evaluator.run(cases, corpus);
        GoldSetComparisonReport comparison = runComparator.compare(
                "isolated-vector/" + identity.embeddingModelId(),
                vectorRun,
                "bm25",
                bm25Run);
        return new IsolatedBenchmarkReport(identity, metadata, comparison);
    }

    private void requireReadyIndex(BenchmarkDatasetIdentity identity, List<GoldSetChunk> corpus) {
        BenchmarkCheckpoint checkpoint = checkpointStore.load()
                .orElseThrow(() -> new BenchmarkOperationException("BENCHMARK_INDEX_NOT_READY"));
        int corpusSize = corpus == null ? 0 : corpus.size();
        if (!identity.equals(checkpoint.identity())
                || checkpoint.status() != BenchmarkCheckpointStatus.COMPLETED
                || checkpoint.completedChunks() != checkpoint.totalChunks()
                || checkpoint.totalChunks() != corpusSize) {
            throw new BenchmarkOperationException("BENCHMARK_INDEX_NOT_READY");
        }
        if (index.count(identity) != corpusSize) {
            throw new BenchmarkOperationException("BENCHMARK_INDEX_COUNT_MISMATCH");
        }
    }
}
