package io.github.imzmq.interview.knowledge.infrastructure.evaluation;

import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetComparisonReport;

import java.nio.file.Path;

/** Result metadata for one file-driven retrieval benchmark comparison. */
public record GoldSetBenchmarkFileResult(
        int goldCaseCount,
        int corpusChunkCount,
        Path outputFile,
        GoldSetComparisonReport report
) {
}
