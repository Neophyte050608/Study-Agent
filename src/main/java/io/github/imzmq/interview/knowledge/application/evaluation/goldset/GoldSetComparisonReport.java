package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import java.util.List;

/** Aggregate and per-query differences between two runs over the same gold set. */
public record GoldSetComparisonReport(
        String candidateLabel,
        String baselineLabel,
        GoldSetMetricsReport candidateMetrics,
        GoldSetMetricsReport baselineMetrics,
        GoldSetMetricDelta metricDelta,
        int candidateWins,
        int baselineWins,
        int ties,
        List<GoldSetCaseComparison> cases
) {
    public GoldSetComparisonReport {
        candidateLabel = normalizeLabel(candidateLabel, "candidate");
        baselineLabel = normalizeLabel(baselineLabel, "baseline");
        candidateMetrics = candidateMetrics == null ? GoldSetMetricsReport.empty() : candidateMetrics;
        baselineMetrics = baselineMetrics == null ? GoldSetMetricsReport.empty() : baselineMetrics;
        metricDelta = metricDelta == null
                ? GoldSetMetricDelta.between(candidateMetrics, baselineMetrics)
                : metricDelta;
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    private static String normalizeLabel(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
