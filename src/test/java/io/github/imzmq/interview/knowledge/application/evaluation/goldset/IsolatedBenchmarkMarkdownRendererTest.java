package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IsolatedBenchmarkMarkdownRendererTest {

    @Test
    void rendersDisclaimerAndReproducibilityMetadata() {
        BenchmarkDatasetIdentity identity = new BenchmarkDatasetIdentity(
                "generic-rag", "a".repeat(64), "embedding-3", 2048);
        BenchmarkRunMetadata metadata = new BenchmarkRunMetadata(
                "b".repeat(64), "benchmark-collection", "v1", 20, 1, 3);
        GoldSetComparisonReport comparison = new GoldSetComparisonReport(
                "isolated-vector/embedding-3", "bm25",
                GoldSetMetricsReport.empty(), GoldSetMetricsReport.empty(), null,
                0, 0, 0, List.of());
        IsolatedBenchmarkReport report = new IsolatedBenchmarkReport(identity, metadata, comparison);

        String markdown = new IsolatedBenchmarkMarkdownRenderer(
                new GoldSetComparisonMarkdownRenderer()).render(report);

        assertThat(markdown)
                .contains("本报告比较隔离向量检索与本地 BM25，不代表完整生产混合检索链路。")
                .contains("Dataset: `generic-rag`")
                .contains("Corpus SHA-256: `" + "a".repeat(64) + "`")
                .contains("Query SHA-256: `" + "b".repeat(64) + "`")
                .contains("Collection/schema: `benchmark-collection` / `v1`")
                .contains("Embedding dimension: `2048`")
                .contains("Top-K: `20`")
                .contains("Candidate: `isolated-vector/embedding-3`");
    }
}
