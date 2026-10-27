package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

/** Candidate metrics minus baseline metrics for one comparable evaluation run. */
public record GoldSetMetricDelta(
        double recallAt5,
        double recallAt10,
        double recallAt20,
        double hitRateAt5,
        double hitRateAt10,
        double hitRateAt20,
        double mrr,
        double ndcgAt10
) {
    public static GoldSetMetricDelta between(
            GoldSetMetricsReport candidate,
            GoldSetMetricsReport baseline
    ) {
        GoldSetMetricsReport left = candidate == null ? GoldSetMetricsReport.empty() : candidate;
        GoldSetMetricsReport right = baseline == null ? GoldSetMetricsReport.empty() : baseline;
        return new GoldSetMetricDelta(
                left.recallAt5() - right.recallAt5(),
                left.recallAt10() - right.recallAt10(),
                left.recallAt20() - right.recallAt20(),
                left.hitRateAt5() - right.hitRateAt5(),
                left.hitRateAt10() - right.hitRateAt10(),
                left.hitRateAt20() - right.hitRateAt20(),
                left.mrr() - right.mrr(),
                left.ndcgAt10() - right.ndcgAt10()
        );
    }
}
