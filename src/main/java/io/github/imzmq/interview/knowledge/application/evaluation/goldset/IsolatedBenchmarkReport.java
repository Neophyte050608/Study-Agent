package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

/** Honest comparison report tied to a specific isolated dataset identity. */
public record IsolatedBenchmarkReport(
        BenchmarkDatasetIdentity identity,
        BenchmarkRunMetadata metadata,
        GoldSetComparisonReport comparison
) {
}
