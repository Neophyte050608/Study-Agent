package io.github.imzmq.interview.chat.infrastructure.milvus;

import io.github.imzmq.interview.chat.application.memory.ChatMemoryProjectionException;
import io.github.imzmq.interview.chat.application.memory.ChatMemoryProperties;
import io.milvus.client.MilvusClient;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.grpc.FieldSchema;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.index.CreateIndexParam;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.chat.memory.enabled", havingValue = "true")
public class ChatMemoryMilvusSchemaInitializer {

    private static final String DESCRIPTION_PREFIX = "study-agent-chat-memory;embedding_model_id=";

    private final MilvusServiceClient rootClient;
    private final ChatMemoryProperties properties;
    private volatile boolean ready;
    private volatile String failureCode = "MEMORY_PROJECTION_SCHEMA_UNAVAILABLE";

    public ChatMemoryMilvusSchemaInitializer(
            MilvusServiceClient rootClient,
            ChatMemoryProperties properties) {
        this.rootClient = rootClient;
        this.properties = properties;
    }

    @PostConstruct
    public void initialize() {
        ready = false;
        try {
            MilvusClient client = deadlineClient();
            boolean exists = requiredData(client.hasCollection(HasCollectionParam.newBuilder()
                    .withCollectionName(properties.getCollectionName())
                    .build()));
            if (exists) {
                DescribeCollectionResponse description = requiredData(client.describeCollection(
                        DescribeCollectionParam.newBuilder()
                                .withCollectionName(properties.getCollectionName())
                                .build()));
                if (!isCompatible(description)) {
                    failureCode = "MEMORY_PROJECTION_SCHEMA_INCOMPATIBLE";
                    log.error("Chat memory Milvus schema is incompatible; projection remains disabled");
                    return;
                }
                load(client);
            } else if (properties.isInitializeSchema()) {
                createAndLoad(client);
            } else {
                failureCode = "MEMORY_PROJECTION_SCHEMA_UNAVAILABLE";
                log.warn("Chat memory Milvus collection is absent and schema initialization is disabled");
                return;
            }
            ready = true;
        } catch (ChatMemoryProjectionException exception) {
            failureCode = exception.code();
            log.warn("Chat memory Milvus schema initialization failed: code={}", exception.code());
        } catch (RuntimeException exception) {
            failureCode = "MEMORY_PROJECTION_SCHEMA_UNAVAILABLE";
            log.warn("Chat memory Milvus schema initialization failed: code={}", failureCode);
        }
    }

    public boolean isReady() {
        return ready;
    }

    public void requireReady() {
        if (!ready) {
            throw new ChatMemoryProjectionException(failureCode);
        }
    }

    private void createAndLoad(MilvusClient client) {
        assertSuccessWithData(client.createCollection(CreateCollectionParam.newBuilder()
                .withCollectionName(properties.getCollectionName())
                .withDescription(DESCRIPTION_PREFIX + properties.getEmbeddingModelId())
                .withEnableDynamicField(false)
                .withFieldTypes(expectedFields())
                .build()));
        assertSuccessWithData(client.createIndex(CreateIndexParam.newBuilder()
                .withCollectionName(properties.getCollectionName())
                .withFieldName("embedding")
                .withIndexName("idx_chat_memory_embedding")
                .withIndexType(IndexType.HNSW)
                .withMetricType(MetricType.COSINE)
                .withExtraParam("{\"M\":16,\"efConstruction\":200}")
                .withSyncMode(true)
                .build()));
        load(client);
    }

    private void load(MilvusClient client) {
        assertSuccessWithData(client.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(properties.getCollectionName())
                .withSyncLoad(true)
                .build()));
    }

    private List<FieldType> expectedFields() {
        return List.of(
                varchar("projection_id", 128, true),
                scalar("memory_id", DataType.Int64),
                varchar("user_id", 191, false),
                scalar("memory_version", DataType.Int64),
                varchar("content_hash", 64, false),
                varchar("category", 32, false),
                varchar("embedding_model_id", 128, false),
                FieldType.newBuilder().withName("embedding")
                        .withDataType(DataType.FloatVector)
                        .withDimension(properties.getEmbeddingDimension()).build());
    }

    private FieldType varchar(String name, int length, boolean primary) {
        return FieldType.newBuilder().withName(name)
                .withDataType(DataType.VarChar)
                .withMaxLength(length)
                .withPrimaryKey(primary)
                .withAutoID(false)
                .build();
    }

    private FieldType scalar(String name, DataType type) {
        return FieldType.newBuilder().withName(name).withDataType(type).build();
    }

    private boolean isCompatible(DescribeCollectionResponse response) {
        if (response == null || response.getSchema() == null
                || !response.getSchema().getDescription()
                .equals(DESCRIPTION_PREFIX + properties.getEmbeddingModelId())) {
            return false;
        }
        Map<String, FieldSchema> actual = new LinkedHashMap<>();
        response.getSchema().getFieldsList().forEach(field -> actual.put(field.getName(), field));
        return !response.getSchema().getEnableDynamicField()
                && hasVarchar(actual, "projection_id", true, 128)
                && !actual.get("projection_id").getAutoID()
                && hasField(actual, "memory_id", DataType.Int64, false)
                && hasVarchar(actual, "user_id", false, 191)
                && hasField(actual, "memory_version", DataType.Int64, false)
                && hasVarchar(actual, "content_hash", false, 64)
                && hasVarchar(actual, "category", false, 32)
                && hasVarchar(actual, "embedding_model_id", false, 128)
                && hasField(actual, "embedding", DataType.FloatVector, false)
                && Integer.toString(properties.getEmbeddingDimension())
                .equals(typeParam(actual.get("embedding"), "dim"));
    }

    private boolean hasVarchar(
            Map<String, FieldSchema> fields, String name, boolean primary, int maxLength) {
        return hasField(fields, name, DataType.VarChar, primary)
                && Integer.toString(maxLength).equals(typeParam(fields.get(name), "max_length"));
    }

    private boolean hasField(
            Map<String, FieldSchema> fields, String name, DataType type, boolean primary) {
        FieldSchema field = fields.get(name);
        return field != null && field.getDataType() == type && field.getIsPrimaryKey() == primary;
    }

    private String typeParam(FieldSchema field, String key) {
        if (field == null) {
            return null;
        }
        return field.getTypeParamsList().stream()
                .filter(parameter -> key.equals(parameter.getKey()))
                .map(parameter -> parameter.getValue())
                .findFirst()
                .orElse(null);
    }

    private MilvusClient deadlineClient() {
        return rootClient.withTimeout(properties.getRpcTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }

    private <T> T requiredData(R<T> response) {
        assertSuccess(response);
        if (response.getData() == null) {
            throw new ChatMemoryProjectionException("MEMORY_PROJECTION_RESPONSE_MISSING");
        }
        return response.getData();
    }

    private void assertSuccessWithData(R<?> response) {
        requiredData(response);
    }

    private void assertSuccess(R<?> response) {
        if (response == null || response.getException() != null) {
            throw new ChatMemoryProjectionException("MEMORY_PROJECTION_SDK_ERROR",
                    response == null ? null : response.getException());
        }
        if (!Integer.valueOf(R.Status.Success.getCode()).equals(response.getStatus())) {
            throw new ChatMemoryProjectionException("MEMORY_PROJECTION_STATUS_FAILED");
        }
    }
}
