package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

/** Renders scope limitations and reproducibility metadata before comparison metrics. */
public final class IsolatedBenchmarkMarkdownRenderer {

    private static final String DISCLAIMER =
            "本报告比较隔离向量检索与本地 BM25，不代表完整生产混合检索链路。";

    private final GoldSetComparisonMarkdownRenderer comparisonRenderer;

    public IsolatedBenchmarkMarkdownRenderer(GoldSetComparisonMarkdownRenderer comparisonRenderer) {
        this.comparisonRenderer = comparisonRenderer;
    }

    public String render(IsolatedBenchmarkReport report) {
        BenchmarkDatasetIdentity identity = report.identity();
        BenchmarkRunMetadata metadata = report.metadata();
        return new StringBuilder()
                .append("> **范围声明：** ").append(DISCLAIMER).append("\n\n")
                .append("## Run metadata\n\n")
                .append("- Dataset: `").append(code(identity.datasetId())).append("`\n")
                .append("- Corpus SHA-256: `").append(code(identity.datasetVersion())).append("`\n")
                .append("- Query SHA-256: `").append(code(metadata.queryFingerprint())).append("`\n")
                .append("- Collection/schema: `").append(code(metadata.collectionName()))
                .append("` / `").append(code(metadata.schemaVersion())).append("`\n")
                .append("- Embedding model: `").append(code(identity.embeddingModelId())).append("`\n")
                .append("- Embedding dimension: `").append(identity.embeddingDimension()).append("`\n")
                .append("- Top-K: `").append(metadata.topK()).append("`\n")
                .append("- Queries/chunks: `").append(metadata.queryCount())
                .append("` / `").append(metadata.corpusChunkCount()).append("`\n\n")
                .append(comparisonRenderer.render(report.comparison()))
                .toString();
    }

    private String code(String value) {
        return value == null ? "" : value.replace("`", "\\`");
    }
}
