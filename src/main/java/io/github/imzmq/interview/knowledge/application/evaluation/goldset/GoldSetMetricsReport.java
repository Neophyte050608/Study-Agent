package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

/** Aggregate chunk-level retrieval metrics for a gold-set run. */
public record GoldSetMetricsReport(
        int totalCases,
        double recallAt5,
        double recallAt10,
        double recallAt20,
        double hitRateAt5,
        double hitRateAt10,
        double hitRateAt20,
        double mrr,
        double ndcgAt10
) {
    public static GoldSetMetricsReport empty() {
        return new GoldSetMetricsReport(0, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D);
    }
}
