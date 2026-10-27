package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GoldSetComparisonMarkdownRendererTest {

    @Test
    void rendersDeterministicSummaryMetricDeltasAndEscapedCaseRows() {
        GoldSetMetricsReport candidateMetrics = new GoldSetMetricsReport(
                2, 0.75, 0.8, 0.9, 0.5, 0.75, 1.0, 0.625, 0.7
        );
        GoldSetMetricsReport baselineMetrics = new GoldSetMetricsReport(
                2, 0.5, 0.7, 0.8, 0.5, 0.5, 1.0, 0.5, 0.6
        );
        GoldSetMetricDelta delta = GoldSetMetricDelta.between(candidateMetrics, baselineMetrics);
        GoldSetCaseComparison comparison = new GoldSetCaseComparison(
                "q|1",
                "Redis\n缓存?",
                "cache",
                candidateMetrics,
                baselineMetrics,
                GoldSetComparisonOutcome.CANDIDATE_WIN
        );
        GoldSetComparisonReport report = new GoldSetComparisonReport(
                "production",
                "bm25",
                candidateMetrics,
                baselineMetrics,
                delta,
                1,
                0,
                0,
                List.of(comparison)
        );

        String markdown = new GoldSetComparisonMarkdownRenderer().render(report);

        assertThat(markdown)
                .startsWith("# Retrieval Evaluation Comparison\n")
                .contains("Candidate: `production`")
                .contains("Baseline: `bm25`")
                .contains("| Recall@5 | 0.7500 | 0.5000 | +0.2500 |")
                .contains("Candidate wins: **1**, baseline wins: **0**, ties: **0**")
                .contains("| q\\|1 | cache | CANDIDATE_WIN | 0.6250 | 0.5000 | Redis 缓存? |");
    }
}
