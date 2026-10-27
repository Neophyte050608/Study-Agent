package io.github.imzmq.interview.knowledge.infrastructure.evaluation;

import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkIndexingResult;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkOperation;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.IsolatedBenchmarkReport;

import java.nio.file.Path;

/** Safe summary of one explicitly selected retrieval-benchmark operation. */
public record RetrievalBenchmarkFileResult(
        BenchmarkOperation operation,
        int goldCaseCount,
        int corpusChunkCount,
        Path outputFile,
        BenchmarkIndexingResult indexingResult,
        IsolatedBenchmarkReport report
) {
}
