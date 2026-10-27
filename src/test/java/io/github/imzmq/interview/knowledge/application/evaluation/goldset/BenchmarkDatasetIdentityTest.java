package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BenchmarkDatasetIdentityTest {

    @Test
    void acceptsPortableDatasetIdentity() {
        BenchmarkDatasetIdentity identity = new BenchmarkDatasetIdentity(
                "rag.regression-2026_10", "a".repeat(64), "embedding-3", 2048);

        assertThat(identity.datasetId()).isEqualTo("rag.regression-2026_10");
        assertThat(identity.datasetVersion()).isEqualTo("a".repeat(64));
    }

    @Test
    void normalizesIdentityText() {
        BenchmarkDatasetIdentity identity = new BenchmarkDatasetIdentity(
                "  generic-rag  ", "A".repeat(64), "  embedding-3  ", 2048);

        assertThat(identity.datasetId()).isEqualTo("generic-rag");
        assertThat(identity.datasetVersion()).isEqualTo("a".repeat(64));
        assertThat(identity.embeddingModelId()).isEqualTo("embedding-3");
    }

    @Test
    void rejectsUnsafeDatasetId() {
        assertThatThrownBy(() -> new BenchmarkDatasetIdentity(
                "rag data/*", "a".repeat(64), "embedding-3", 2048))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("BENCHMARK_DATASET_ID_INVALID");
    }

    @Test
    void rejectsInvalidVersionModelAndDimension() {
        assertThatThrownBy(() -> new BenchmarkDatasetIdentity(
                "rag", "not-a-sha", "embedding-3", 2048))
                .hasMessage("BENCHMARK_DATASET_VERSION_INVALID");
        assertThatThrownBy(() -> new BenchmarkDatasetIdentity(
                "rag", "a".repeat(64), " ", 2048))
                .hasMessage("BENCHMARK_EMBEDDING_MODEL_INVALID");
        assertThatThrownBy(() -> new BenchmarkDatasetIdentity(
                "rag", "a".repeat(64), "embedding-3", 0))
                .hasMessage("BENCHMARK_EMBEDDING_DIMENSION_INVALID");
    }

    @Test
    void calculatesLowercaseSha256() {
        assertThat(BenchmarkFingerprint.sha256("abc".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
