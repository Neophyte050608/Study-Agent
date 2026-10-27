package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import java.util.List;

/** Complete output of one chunk-level gold-set retrieval evaluation run. */
public record GoldSetEvaluationRun(
        int topK,
        GoldSetMetricsReport metrics,
        List<GoldSetRetrievalResult> results
) {
    public GoldSetEvaluationRun {
        metrics = metrics == null ? GoldSetMetricsReport.empty() : metrics;
        results = results == null ? List.of() : List.copyOf(results);
    }
}
