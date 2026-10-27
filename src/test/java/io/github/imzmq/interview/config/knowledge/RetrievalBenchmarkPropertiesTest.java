package io.github.imzmq.interview.config.knowledge;

import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkOperation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrievalBenchmarkPropertiesTest {

    @Test
    void defaultsToDisabledValidationWithoutDestructiveConfirmation() {
        RetrievalBenchmarkProperties properties = new RetrievalBenchmarkProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getOperation()).isEqualTo(BenchmarkOperation.VALIDATE);
        assertThat(properties.getCollectionName()).isEqualTo("study_agent_retrieval_benchmark");
        assertThat(properties.getBatchSize()).isEqualTo(64);
        assertThat(properties.getEmbeddingModelId()).isEqualTo("embedding-3");
        assertThat(properties.getEmbeddingDimension()).isEqualTo(2048);
        assertThat(properties.getCleanupConfirmation()).isEmpty();
    }

    @Test
    void normalizesOperatorSuppliedText() {
        RetrievalBenchmarkProperties properties = new RetrievalBenchmarkProperties();

        properties.setDatasetId("  generic-rag  ");
        properties.setCleanupConfirmation(null);

        assertThat(properties.getDatasetId()).isEqualTo("generic-rag");
        assertThat(properties.getCleanupConfirmation()).isEmpty();
    }

    @Test
    void requiresConfiguredPathsOnlyWhenRequested() {
        RetrievalBenchmarkProperties properties = new RetrievalBenchmarkProperties();

        assertThatThrownBy(properties::requireGoldPath)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gold-file");
    }
}
