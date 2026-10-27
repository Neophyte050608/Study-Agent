package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Evaluates only the isolated benchmark vector index using exact chunk identities. */
public final class IsolatedVectorEvaluator {

    static final int TOP_K = 20;

    private final BenchmarkIndex index;
    private final GoldSetMetricsCalculator metricsCalculator;

    public IsolatedVectorEvaluator(BenchmarkIndex index, GoldSetMetricsCalculator metricsCalculator) {
        this.index = index;
        this.metricsCalculator = metricsCalculator;
    }

    public GoldSetEvaluationRun run(BenchmarkDatasetIdentity identity, List<GoldSetCase> cases) {
        List<GoldSetRetrievalResult> results = new ArrayList<>();
        if (cases != null) {
            for (GoldSetCase evalCase : cases) {
                if (evalCase == null || evalCase.question() == null || evalCase.question().isBlank()) {
                    continue;
                }
                List<String> retrieved = distinctChunkIds(index.search(identity, evalCase.question(), TOP_K));
                int firstRelevantRank = firstRelevantRank(evalCase, retrieved);
                results.add(new GoldSetRetrievalResult(
                        evalCase.queryId(),
                        evalCase.question(),
                        evalCase.category(),
                        evalCase.groundTruth(),
                        evalCase.relevance(),
                        retrieved,
                        firstRelevantRank > 0,
                        firstRelevantRank));
            }
        }
        return new GoldSetEvaluationRun(TOP_K, metricsCalculator.calculate(results), results);
    }

    private List<String> distinctChunkIds(List<BenchmarkIndexMatch> matches) {
        LinkedHashSet<String> chunkIds = new LinkedHashSet<>();
        if (matches != null) {
            for (BenchmarkIndexMatch match : matches) {
                if (match != null && match.chunkId() != null && !match.chunkId().isBlank()) {
                    chunkIds.add(match.chunkId().trim());
                }
            }
        }
        return List.copyOf(chunkIds);
    }

    private int firstRelevantRank(GoldSetCase evalCase, List<String> retrieved) {
        for (int index = 0; index < retrieved.size(); index++) {
            if (evalCase.isRelevant(retrieved.get(index))) {
                return index + 1;
            }
        }
        return -1;
    }
}
