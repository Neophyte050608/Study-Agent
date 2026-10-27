package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import org.springframework.stereotype.Service;

import java.util.List;

/** Runs production retrieval and the local BM25 baseline over one identical gold set. */
@Service
public final class GoldSetBenchmarkComparisonService {

    private static final String PRODUCTION_LABEL = "production";
    private static final String BM25_LABEL = "bm25";

    private final GoldSetRetrievalEvaluator retrievalEvaluator;
    private final GoldSetBm25Evaluator bm25Evaluator;
    private final GoldSetRunComparator runComparator;

    public GoldSetBenchmarkComparisonService(
            GoldSetRetrievalEvaluator retrievalEvaluator,
            GoldSetBm25Evaluator bm25Evaluator,
            GoldSetRunComparator runComparator
    ) {
        this.retrievalEvaluator = retrievalEvaluator;
        this.bm25Evaluator = bm25Evaluator;
        this.runComparator = runComparator;
    }

    public GoldSetComparisonReport compare(List<GoldSetCase> cases, List<GoldSetChunk> corpus) {
        GoldSetEvaluationRun productionRun = retrievalEvaluator.run(cases);
        GoldSetEvaluationRun baselineRun = bm25Evaluator.run(cases, corpus);
        return runComparator.compare(PRODUCTION_LABEL, productionRun, BM25_LABEL, baselineRun);
    }
}
