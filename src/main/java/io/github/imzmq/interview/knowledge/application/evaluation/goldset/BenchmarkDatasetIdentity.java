package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import java.util.Locale;
import java.util.regex.Pattern;

/** Stable identity of one corpus version under one embedding model. */
public record BenchmarkDatasetIdentity(
        String datasetId,
        String datasetVersion,
        String embeddingModelId,
        int embeddingDimension
) {
    private static final Pattern DATASET_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern SHA256 = Pattern.compile("[a-f0-9]{64}");

    public BenchmarkDatasetIdentity {
        datasetId = normalize(datasetId);
        datasetVersion = normalize(datasetVersion).toLowerCase(Locale.ROOT);
        embeddingModelId = normalize(embeddingModelId);
        if (!DATASET_ID.matcher(datasetId).matches()) {
            throw new IllegalArgumentException("BENCHMARK_DATASET_ID_INVALID");
        }
        if (!SHA256.matcher(datasetVersion).matches()) {
            throw new IllegalArgumentException("BENCHMARK_DATASET_VERSION_INVALID");
        }
        if (embeddingModelId.isEmpty()) {
            throw new IllegalArgumentException("BENCHMARK_EMBEDDING_MODEL_INVALID");
        }
        if (embeddingDimension < 1 || embeddingDimension > 65_536) {
            throw new IllegalArgumentException("BENCHMARK_EMBEDDING_DIMENSION_INVALID");
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
