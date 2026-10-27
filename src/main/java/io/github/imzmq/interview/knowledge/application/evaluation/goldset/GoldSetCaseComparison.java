package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

/** Aligned per-query comparison with independently calculated metrics for each run. */
public record GoldSetCaseComparison(
        String queryId,
        String question,
        String category,
        GoldSetMetricsReport candidateMetrics,
        GoldSetMetricsReport baselineMetrics,
        GoldSetComparisonOutcome outcome
) {
    public GoldSetCaseComparison {
        queryId = queryId == null ? "" : queryId;
        question = question == null ? "" : question;
        category = category == null ? "" : category;
        candidateMetrics = candidateMetrics == null ? GoldSetMetricsReport.empty() : candidateMetrics;
        baselineMetrics = baselineMetrics == null ? GoldSetMetricsReport.empty() : baselineMetrics;
        outcome = outcome == null ? GoldSetComparisonOutcome.TIE : outcome;
    }
}
