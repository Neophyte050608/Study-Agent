package io.github.imzmq.interview.knowledge.infrastructure.evaluation.milvus;

import io.github.imzmq.interview.config.knowledge.RetrievalBenchmarkProperties;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkDatasetIdentity;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkIndexMatch;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetChunk;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.R;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.FlushParam;
import io.milvus.param.collection.HasCollectionParam;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "RUN_MILVUS_INTEGRATION_TESTS", matches = "true")
class MilvusBenchmarkIndexIntegrationTest {

    private static final Duration VISIBILITY_TIMEOUT = Duration.ofSeconds(30);
    private static final String MODEL_ID = "deterministic-test-embedding";
    private static final int DIMENSION = 3;

    @Test
    void upsertsCountsSearchesAndDeletesOnlyTheRequestedDataset() throws Exception {
        String collectionName = "retrieval_benchmark_it_"
                + UUID.randomUUID().toString().replace("-", "");
        MilvusServiceClient client = new MilvusServiceClient(ConnectParam.newBuilder()
                .withHost(environment("TEST_MILVUS_HOST", "localhost"))
                .withPort(Integer.parseInt(environment("TEST_MILVUS_PORT", "19530")))
                .withConnectTimeout(10, TimeUnit.SECONDS)
                .build());
        try {
            assertThat(collectionExists(client, collectionName)).isFalse();
            RetrievalBenchmarkProperties properties = properties(collectionName);
            MilvusBenchmarkIndex index = new MilvusBenchmarkIndex(
                    client,
                    new DeterministicEmbeddingModel(),
                    new MilvusBenchmarkSchema(client, properties),
                    properties);
            BenchmarkDatasetIdentity first = identity("integration-first", "a");
            BenchmarkDatasetIdentity second = identity("integration-second", "b");

            index.upsertBatch(first, List.of(
                    chunk("first-alpha", "alpha concept"),
                    chunk("first-beta", "beta concept")));
            index.upsertBatch(second, List.of(chunk("second-alpha", "alpha concept")));
            flush(client, collectionName);

            awaitCount(index, first, 2);
            awaitCount(index, second, 1);
            List<BenchmarkIndexMatch> matches = awaitSearch(index, first, "alpha");
            assertThat(matches).extracting(BenchmarkIndexMatch::chunkId)
                    .containsExactly("first-alpha", "first-beta");

            index.deleteDataset(first.datasetId());
            flush(client, collectionName);
            awaitCount(index, first, 0);
            assertThat(index.count(second)).isEqualTo(1);
            assertThat(awaitSearch(index, second, "alpha"))
                    .extracting(BenchmarkIndexMatch::chunkId)
                    .containsExactly("second-alpha");
        } finally {
            if (collectionExists(client, collectionName)) {
                assertSuccess(client.dropCollection(DropCollectionParam.newBuilder()
                        .withCollectionName(collectionName)
                        .build()));
            }
            client.close();
        }
    }

    private RetrievalBenchmarkProperties properties(String collectionName) {
        RetrievalBenchmarkProperties properties = new RetrievalBenchmarkProperties();
        properties.setCollectionName(collectionName);
        properties.setEmbeddingModelId(MODEL_ID);
        properties.setEmbeddingDimension(DIMENSION);
        return properties;
    }

    private BenchmarkDatasetIdentity identity(String datasetId, String versionMarker) {
        return new BenchmarkDatasetIdentity(
                datasetId,
                versionMarker.repeat(64),
                MODEL_ID,
                DIMENSION);
    }

    private GoldSetChunk chunk(String chunkId, String content) {
        return new GoldSetChunk(chunkId, "", 0, content, "", "", "");
    }

    private void flush(MilvusServiceClient client, String collectionName) {
        assertSuccess(client.flush(FlushParam.newBuilder()
                .addCollectionName(collectionName)
                .withSyncFlush(true)
                .withSyncFlushWaitingInterval(100L)
                .withSyncFlushWaitingTimeout(VISIBILITY_TIMEOUT.toMillis())
                .build()));
    }

    private void awaitCount(
            MilvusBenchmarkIndex index,
            BenchmarkDatasetIdentity identity,
            long expected
    ) throws InterruptedException {
        long deadline = System.nanoTime() + VISIBILITY_TIMEOUT.toNanos();
        do {
            if (index.count(identity) == expected) {
                return;
            }
            Thread.sleep(250L);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("benchmark count did not become visible before the timeout");
    }

    private List<BenchmarkIndexMatch> awaitSearch(
            MilvusBenchmarkIndex index,
            BenchmarkDatasetIdentity identity,
            String query
    ) throws InterruptedException {
        long deadline = System.nanoTime() + VISIBILITY_TIMEOUT.toNanos();
        do {
            List<BenchmarkIndexMatch> matches = index.search(identity, query, 20);
            if (!matches.isEmpty()) {
                return matches;
            }
            Thread.sleep(250L);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("benchmark vectors did not become searchable before the timeout");
    }

    private boolean collectionExists(MilvusServiceClient client, String collectionName) {
        R<Boolean> response = client.hasCollection(HasCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .build());
        if (response == null || response.getException() != null
                || !Integer.valueOf(R.Status.Success.getCode()).equals(response.getStatus())) {
            return false;
        }
        return Boolean.TRUE.equals(response.getData());
    }

    private void assertSuccess(R<?> response) {
        assertThat(response).as("Milvus response").isNotNull();
        assertThat(response.getException()).as("Milvus SDK exception").isNull();
        assertThat(response.getStatus()).as("Milvus business status")
                .isEqualTo(R.Status.Success.getCode());
    }

    private String environment(String name, String fallback) {
        return System.getenv().getOrDefault(name, fallback).trim();
    }

    private static final class DeterministicEmbeddingModel implements EmbeddingModel {

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> results = request.getInstructions().stream()
                    .map(this::vector)
                    .map(vector -> new Embedding(vector, 0))
                    .toList();
            return new EmbeddingResponse(results);
        }

        @Override
        public float[] embed(String text) {
            return vector(text);
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            return texts.stream().map(this::vector).toList();
        }

        @Override
        public float[] embed(Document document) {
            return vector(document.getText());
        }

        private float[] vector(String text) {
            String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
            if (normalized.contains("alpha")) {
                return new float[]{1F, 0F, 0F};
            }
            if (normalized.contains("beta")) {
                return new float[]{0F, 1F, 0F};
            }
            return new float[]{0F, 0F, 1F};
        }
    }
}
