package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import org.springframework.stereotype.Component;

import java.util.Locale;

/** Renders a deterministic, dependency-free Markdown artifact for one run comparison. */
@Component
public final class GoldSetComparisonMarkdownRenderer {

    public String render(GoldSetComparisonReport report) {
        if (report == null) {
            throw new IllegalArgumentException("report must not be null");
        }
        StringBuilder markdown = new StringBuilder();
        markdown.append("# Retrieval Evaluation Comparison\n\n")
                .append("Candidate: `").append(escapeInlineCode(report.candidateLabel())).append("`  \n")
                .append("Baseline: `").append(escapeInlineCode(report.baselineLabel())).append("`\n\n")
                .append("## Aggregate metrics\n\n")
                .append("| Metric | Candidate | Baseline | Delta |\n")
                .append("| --- | ---: | ---: | ---: |\n");
        appendMetric(markdown, "Recall@5", report.candidateMetrics().recallAt5(), report.baselineMetrics().recallAt5(), report.metricDelta().recallAt5());
        appendMetric(markdown, "Recall@10", report.candidateMetrics().recallAt10(), report.baselineMetrics().recallAt10(), report.metricDelta().recallAt10());
        appendMetric(markdown, "Recall@20", report.candidateMetrics().recallAt20(), report.baselineMetrics().recallAt20(), report.metricDelta().recallAt20());
        appendMetric(markdown, "HitRate@5", report.candidateMetrics().hitRateAt5(), report.baselineMetrics().hitRateAt5(), report.metricDelta().hitRateAt5());
        appendMetric(markdown, "HitRate@10", report.candidateMetrics().hitRateAt10(), report.baselineMetrics().hitRateAt10(), report.metricDelta().hitRateAt10());
        appendMetric(markdown, "HitRate@20", report.candidateMetrics().hitRateAt20(), report.baselineMetrics().hitRateAt20(), report.metricDelta().hitRateAt20());
        appendMetric(markdown, "MRR", report.candidateMetrics().mrr(), report.baselineMetrics().mrr(), report.metricDelta().mrr());
        appendMetric(markdown, "NDCG@10", report.candidateMetrics().ndcgAt10(), report.baselineMetrics().ndcgAt10(), report.metricDelta().ndcgAt10());

        markdown.append("\n## Per-query comparison\n\n")
                .append("Candidate wins: **").append(report.candidateWins())
                .append("**, baseline wins: **").append(report.baselineWins())
                .append("**, ties: **").append(report.ties()).append("**\n\n")
                .append("| Query ID | Category | Outcome | Candidate MRR | Baseline MRR | Question |\n")
                .append("| --- | --- | --- | ---: | ---: | --- |\n");
        for (GoldSetCaseComparison comparison : report.cases()) {
            markdown.append("| ").append(escapeCell(comparison.queryId()))
                    .append(" | ").append(escapeCell(comparison.category()))
                    .append(" | ").append(comparison.outcome())
                    .append(" | ").append(format(comparison.candidateMetrics().mrr()))
                    .append(" | ").append(format(comparison.baselineMetrics().mrr()))
                    .append(" | ").append(escapeCell(comparison.question()))
                    .append(" |\n");
        }
        return markdown.toString();
    }

    private void appendMetric(StringBuilder output, String name, double candidate, double baseline, double delta) {
        output.append("| ").append(name)
                .append(" | ").append(format(candidate))
                .append(" | ").append(format(baseline))
                .append(" | ").append(formatSigned(delta))
                .append(" |\n");
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private String formatSigned(double value) {
        return String.format(Locale.ROOT, "%+.4f", value);
    }

    private String escapeCell(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("|", "\\|").replaceAll("[\\r\\n]+", " ");
    }

    private String escapeInlineCode(String value) {
        return value == null ? "" : value.replace("`", "\\`");
    }
}
