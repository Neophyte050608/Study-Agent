package io.github.imzmq.interview.chat.infrastructure.milvus;

import io.github.imzmq.interview.chat.application.memory.ChatMemoryProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.R;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.FlushParam;
import io.milvus.param.collection.HasCollectionParam;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.zhipuai.ZhiPuAiEmbeddingModel;
import org.springframework.ai.zhipuai.ZhiPuAiEmbeddingOptions;
import org.springframework.ai.zhipuai.api.ZhiPuAiApi;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ChatMemoryMilvusAdapterIntegrationTest {

    private static final Duration RPC_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration VISIBILITY_TIMEOUT = Duration.ofSeconds(30);

    @Test
    void upsertsSearchesAndDeletesUsingARealMilvusAndEmbeddingProvider() throws Exception {
        String host = property("test.milvus.host");
        String portText = property("test.milvus.port");
        String provider = property("test.embedding.provider");
        String apiKey = secret("test.embedding.api-key", "TEST_EMBEDDING_API_KEY");
        String model = property("test.embedding.model");
        String dimensionText = property("test.embedding.dimension");
        assumeTrue(!host.isBlank() && !portText.isBlank(),
                "set -Dtest.milvus.host and -Dtest.milvus.port to run the real Milvus test");
        assumeTrue(!provider.isBlank() && !apiKey.isBlank()
                        && !model.isBlank() && !dimensionText.isBlank(),
                "set the explicit test.embedding provider, api-key, model and dimension properties");

        int port = positiveInteger(portText, "test.milvus.port");
        int dimension = positiveInteger(dimensionText, "test.embedding.dimension");
        String collectionName = "chat_memory_it_"
                + UUID.randomUUID().toString().replace("-", "");
        MilvusServiceClient client = new MilvusServiceClient(ConnectParam.newBuilder()
                .withHost(host)
                .withPort(port)
                .withConnectTimeout(10, TimeUnit.SECONDS)
                .build());
        boolean cleanupEligible = false;
        try {
            assertThat(requiredData(client.hasCollection(HasCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .build())))
                    .as("the unique integration-test collection must not pre-exist")
                    .isFalse();
            cleanupEligible = true;

            ChatMemoryProperties properties = properties(collectionName, model, dimension);
            ChatMemoryMilvusSchemaInitializer initializer =
                    new ChatMemoryMilvusSchemaInitializer(client, properties);
            initializer.initialize();
            assertThat(requiredData(client.hasCollection(HasCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .build()))).isTrue();
            initializer.requireReady();

            EmbeddingModel embeddingModel = embeddingModel(provider, apiKey, model, dimension);
            ChatMemoryMilvusAdapter adapter =
                    new ChatMemoryMilvusAdapter(client, embeddingModel, initializer, properties);
            String marker = UUID.randomUUID().toString();
            ChatMemoryProjection projection = new ChatMemoryProjection(
                    "1001:1", 1001L, "integration-user", 1L,
                    "integration-hash", "FACT",
                    "My integration test marker is " + marker);

            adapter.upsert(projection, RPC_TIMEOUT);
            flush(client, collectionName);

            ChatMemoryVectorCandidate candidate = awaitCandidate(adapter, marker);
            assertThat(candidate.projectionId()).isEqualTo(projection.projectionId());
            assertThat(candidate.memoryId()).isEqualTo(projection.memoryId());
            assertThat(candidate.memoryVersion()).isEqualTo(projection.memoryVersion());
            assertThat(candidate.contentHash()).isEqualTo(projection.contentHash());
            assertThat(candidate.category()).isEqualTo(projection.category());

            adapter.deleteVersion(
                    projection.userId(), projection.memoryId(), projection.memoryVersion(), RPC_TIMEOUT);
            flush(client, collectionName);
            awaitDeleted(adapter, marker, projection.projectionId());
        } finally {
            try {
                if (cleanupEligible && collectionExists(client, collectionName)) {
                    assertSuccess(client.dropCollection(DropCollectionParam.newBuilder()
                            .withCollectionName(collectionName)
                            .build()));
                }
            } finally {
                client.close(5_000);
            }
        }
    }

    private ChatMemoryVectorCandidate awaitCandidate(
            ChatMemoryMilvusAdapter adapter, String query) throws InterruptedException {
        long deadline = System.nanoTime() + VISIBILITY_TIMEOUT.toNanos();
        do {
            List<ChatMemoryVectorCandidate> candidates =
                    adapter.search("integration-user", query, 5, RPC_TIMEOUT);
            if (!candidates.isEmpty()) {
                return candidates.getFirst();
            }
            Thread.sleep(250);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("projection did not become searchable before the timeout");
    }

    private void awaitDeleted(
            ChatMemoryMilvusAdapter adapter, String query, String projectionId)
            throws InterruptedException {
        long deadline = System.nanoTime() + VISIBILITY_TIMEOUT.toNanos();
        do {
            List<ChatMemoryVectorCandidate> candidates =
                    adapter.search("integration-user", query, 5, RPC_TIMEOUT);
            if (candidates.stream().noneMatch(candidate ->
                    projectionId.equals(candidate.projectionId()))) {
                return;
            }
            Thread.sleep(250);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("deleted projection remained searchable after the timeout");
    }

    private EmbeddingModel embeddingModel(
            String provider, String apiKey, String model, int dimension) {
        return switch (provider.toLowerCase(Locale.ROOT)) {
            case "zhipuai" -> new ZhiPuAiEmbeddingModel(
                    zhipuApi(apiKey),
                    MetadataMode.NONE,
                    ZhiPuAiEmbeddingOptions.builder()
                            .model(model)
                            .dimensions(dimension)
                            .build());
            case "openai" -> new OpenAiEmbeddingModel(
                    openAiApi(apiKey),
                    MetadataMode.NONE,
                    OpenAiEmbeddingOptions.builder()
                            .model(model)
                            .dimensions(dimension)
                            .build());
            default -> throw new IllegalArgumentException(
                    "test.embedding.provider must be zhipuai or openai");
        };
    }

    private ZhiPuAiApi zhipuApi(String apiKey) {
        String baseUrl = property("test.embedding.base-url");
        return baseUrl.isBlank()
                ? new ZhiPuAiApi(apiKey)
                : new ZhiPuAiApi(baseUrl, apiKey);
    }

    private OpenAiApi openAiApi(String apiKey) {
        OpenAiApi.Builder builder = OpenAiApi.builder().apiKey(apiKey);
        String baseUrl = property("test.embedding.base-url");
        if (!baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
    }

    private ChatMemoryProperties properties(
            String collectionName, String model, int dimension) {
        ChatMemoryProperties properties = new ChatMemoryProperties();
        properties.setCollectionName(collectionName);
        properties.setEmbeddingModelId(model);
        properties.setEmbeddingDimension(dimension);
        properties.setInitializeSchema(true);
        properties.setRpcTimeout(RPC_TIMEOUT);
        return properties;
    }

    private void flush(MilvusServiceClient client, String collectionName) {
        assertSuccessWithData(client.flush(FlushParam.newBuilder()
                .addCollectionName(collectionName)
                .withSyncFlush(true)
                .withSyncFlushWaitingInterval(100L)
                .withSyncFlushWaitingTimeout(VISIBILITY_TIMEOUT.toMillis())
                .build()));
    }

    private boolean collectionExists(MilvusServiceClient client, String collectionName) {
        try {
            return requiredData(client.hasCollection(HasCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .build()));
        } catch (RuntimeException ignoredDuringCleanup) {
            return false;
        }
    }

    private int positiveInteger(String text, String propertyName) {
        int value = Integer.parseInt(text);
        if (value < 1) {
            throw new IllegalArgumentException(propertyName + " must be positive");
        }
        return value;
    }

    private String property(String name) {
        return System.getProperty(name, "").trim();
    }

    private String secret(String propertyName, String environmentName) {
        String configured = property(propertyName);
        if (!configured.isBlank()) {
            return configured;
        }
        return System.getenv().getOrDefault(environmentName, "").trim();
    }

    private <T> T requiredData(R<T> response) {
        assertSuccess(response);
        assertThat(response.getData()).as("Milvus response data").isNotNull();
        return response.getData();
    }

    private void assertSuccessWithData(R<?> response) {
        requiredData(response);
    }

    private void assertSuccess(R<?> response) {
        assertThat(response).as("Milvus response").isNotNull();
        assertThat(response.getException()).as("Milvus SDK exception").isNull();
        assertThat(response.getStatus()).as("Milvus business status")
                .isEqualTo(R.Status.Success.getCode());
    }
}
