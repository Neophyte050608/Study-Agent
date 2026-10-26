package io.github.imzmq.interview.chat.infrastructure.milvus;

import io.github.imzmq.interview.chat.application.memory.ChatMemoryProjectionException;
import io.github.imzmq.interview.chat.application.memory.ChatMemoryProperties;
import io.milvus.client.MilvusClient;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.CollectionSchema;
import io.milvus.grpc.DataType;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.grpc.FieldSchema;
import io.milvus.grpc.KeyValuePair;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMemoryMilvusSchemaInitializerTest {

    private final MilvusServiceClient rootClient = mock(MilvusServiceClient.class);
    private final MilvusClient client = mock(MilvusClient.class);
    private final ChatMemoryProperties properties = new ChatMemoryProperties();

    @BeforeEach
    void setUp() {
        properties.setCollectionName("chat_memories");
        properties.setEmbeddingDimension(3);
        properties.setEmbeddingModelId("embedding-test");
        properties.setRpcTimeout(java.time.Duration.ofSeconds(2));
        when(rootClient.withTimeout(2000, TimeUnit.MILLISECONDS)).thenReturn(client);
    }

    @Test
    void createsIndexesAndLoadsIndependentCollectionWhenAbsent() {
        when(client.hasCollection(any())).thenReturn(R.success(false));
        when(client.createCollection(any(io.milvus.param.collection.CreateCollectionParam.class))).thenReturn(R.success(new RpcStatus("ok")));
        when(client.createIndex(any())).thenReturn(R.success(new RpcStatus("ok")));
        when(client.loadCollection(any())).thenReturn(R.success(new RpcStatus("ok")));
        ChatMemoryMilvusSchemaInitializer initializer = initializer();

        initializer.initialize();

        assertThat(initializer.isReady()).isTrue();
        verify(client).createCollection(any(io.milvus.param.collection.CreateCollectionParam.class));
        verify(client).createIndex(any());
        verify(client).loadCollection(any());
    }

    @Test
    void incompatibleExistingSchemaIsNeverDroppedOrRecreated() {
        when(client.hasCollection(any())).thenReturn(R.success(true));
        when(client.describeCollection(any())).thenReturn(R.success(existingSchema(4, "other-model")));
        ChatMemoryMilvusSchemaInitializer initializer = initializer();

        initializer.initialize();

        assertThat(initializer.isReady()).isFalse();
        assertThatThrownBy(initializer::requireReady)
                .isInstanceOf(ChatMemoryProjectionException.class)
                .hasMessage("MEMORY_PROJECTION_SCHEMA_INCOMPATIBLE");
        verify(client, never()).createCollection(any(io.milvus.param.collection.CreateCollectionParam.class));
        verify(client, never()).dropCollection(any(io.milvus.param.collection.DropCollectionParam.class));
    }

    @Test
    void compatibleExistingSchemaIsLoadedWithoutBeingRecreated() {
        when(client.hasCollection(any())).thenReturn(R.success(true));
        when(client.describeCollection(any())).thenReturn(R.success(existingSchema(3, "embedding-test")));
        when(client.loadCollection(any())).thenReturn(R.success(new RpcStatus("ok")));
        ChatMemoryMilvusSchemaInitializer initializer = initializer();

        initializer.initialize();

        assertThat(initializer.isReady()).isTrue();
        verify(client).loadCollection(any());
        verify(client, never()).createCollection(any(io.milvus.param.collection.CreateCollectionParam.class));
    }

    @Test
    void wrongVarcharLengthOrAutoIdMakesExistingSchemaIncompatible() {
        when(client.hasCollection(any())).thenReturn(R.success(true));
        DescribeCollectionResponse wrongLength = existingSchema(3, "embedding-test").toBuilder()
                .setSchema(existingSchema(3, "embedding-test").getSchema().toBuilder()
                        .setFields(0, field("projection_id", DataType.VarChar, true,
                                true, "max_length", "64")))
                .build();
        when(client.describeCollection(any())).thenReturn(R.success(wrongLength));
        ChatMemoryMilvusSchemaInitializer initializer = initializer();

        initializer.initialize();

        assertThat(initializer.isReady()).isFalse();
        assertThatThrownBy(initializer::requireReady)
                .isInstanceOf(ChatMemoryProjectionException.class)
                .hasMessage("MEMORY_PROJECTION_SCHEMA_INCOMPATIBLE");
        verify(client, never()).loadCollection(any());
    }

    @Test
    void nullSdkResponseLeavesProjectionUnavailableWithControlledCode() {
        when(client.hasCollection(any())).thenReturn(null);
        ChatMemoryMilvusSchemaInitializer initializer = initializer();

        initializer.initialize();

        assertThat(initializer.isReady()).isFalse();
        assertThatThrownBy(initializer::requireReady)
                .isInstanceOf(ChatMemoryProjectionException.class)
                .hasMessage("MEMORY_PROJECTION_SDK_ERROR");
    }

    private ChatMemoryMilvusSchemaInitializer initializer() {
        return new ChatMemoryMilvusSchemaInitializer(rootClient, properties);
    }

    private DescribeCollectionResponse existingSchema(int dimension, String modelId) {
        List<FieldSchema> fields = List.of(
                field("projection_id", DataType.VarChar, true, "max_length", "128"),
                field("memory_id", DataType.Int64, false, null, null),
                field("user_id", DataType.VarChar, false, "max_length", "191"),
                field("memory_version", DataType.Int64, false, null, null),
                field("content_hash", DataType.VarChar, false, "max_length", "64"),
                field("category", DataType.VarChar, false, "max_length", "32"),
                field("embedding_model_id", DataType.VarChar, false, "max_length", "128"),
                field("embedding", DataType.FloatVector, false, "dim", Integer.toString(dimension)));
        return DescribeCollectionResponse.newBuilder()
                .setCollectionName("chat_memories")
                .setSchema(CollectionSchema.newBuilder()
                        .setName("chat_memories")
                        .setDescription("study-agent-chat-memory;embedding_model_id=" + modelId)
                        .addAllFields(fields))
                .build();
    }

    private FieldSchema field(String name, DataType type, boolean primary, String key, String value) {
        return field(name, type, primary, false, key, value);
    }

    private FieldSchema field(
            String name, DataType type, boolean primary, boolean autoId,
            String key, String value) {
        FieldSchema.Builder builder = FieldSchema.newBuilder()
                .setName(name)
                .setDataType(type)
                .setIsPrimaryKey(primary)
                .setAutoID(autoId);
        if (key != null) {
            builder.addTypeParams(KeyValuePair.newBuilder().setKey(key).setValue(value));
        }
        return builder.build();
    }
}
