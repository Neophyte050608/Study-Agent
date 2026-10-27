package io.github.imzmq.interview.knowledge.infrastructure.evaluation.milvus;

import io.github.imzmq.interview.config.knowledge.RetrievalBenchmarkProperties;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkOperationException;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.CollectionSchema;
import io.milvus.grpc.DataType;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.grpc.FieldSchema;
import io.milvus.grpc.KeyValuePair;
import io.milvus.param.IndexType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.index.CreateIndexParam;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MilvusBenchmarkSchemaTest {

    private final MilvusServiceClient client = mock(MilvusServiceClient.class);
    private final RetrievalBenchmarkProperties properties = new RetrievalBenchmarkProperties();

    @BeforeEach
    void setUp() {
        properties.setCollectionName("study_agent_retrieval_benchmark");
        properties.setEmbeddingDimension(3);
    }

    @Test
    void doesNotTouchMilvusUntilRequired() {
        new MilvusBenchmarkSchema(client, properties);

        verifyNoInteractions(client);
    }

    @Test
    void createsExpectedGenericSchemaAndLoadsCollection() {
        when(client.hasCollection(any())).thenReturn(R.success(false));
        when(client.createCollection(any(CreateCollectionParam.class)))
                .thenReturn(R.success(new RpcStatus("ok")));
        when(client.createIndex(any(CreateIndexParam.class)))
                .thenReturn(R.success(new RpcStatus("ok")));
        when(client.loadCollection(any())).thenReturn(R.success(new RpcStatus("ok")));
        MilvusBenchmarkSchema schema = new MilvusBenchmarkSchema(client, properties);

        schema.requireReady();

        ArgumentCaptor<CreateCollectionParam> collection = ArgumentCaptor.forClass(CreateCollectionParam.class);
        verify(client).createCollection(collection.capture());
        assertThat(collection.getValue().getCollectionName())
                .isEqualTo("study_agent_retrieval_benchmark");
        assertThat(collection.getValue().getDescription())
                .isEqualTo("study-agent-retrieval-benchmark;schema=1;dimension=3");
        assertThat(collection.getValue().isEnableDynamicField()).isFalse();
        assertThat(collection.getValue().getFieldTypes()).extracting(FieldType::getName)
                .containsExactly("benchmark_vector_id", "dataset_id", "dataset_version", "chunk_id",
                        "content_hash", "embedding_model_id", "embedding");

        ArgumentCaptor<CreateIndexParam> index = ArgumentCaptor.forClass(CreateIndexParam.class);
        verify(client).createIndex(index.capture());
        assertThat(index.getValue().getIndexName()).isEqualTo("idx_benchmark_embedding");
        assertThat(index.getValue().getIndexType()).isEqualTo(IndexType.HNSW);
        assertThat(index.getValue().getExtraParam())
                .containsEntry("index_type", "HNSW")
                .containsEntry("metric_type", "COSINE")
                .containsEntry("params", "{\"M\":16,\"efConstruction\":200}");
        verify(client).loadCollection(any());
    }

    @Test
    void compatibleExistingSchemaIsLoadedOnlyOnce() {
        when(client.hasCollection(any())).thenReturn(R.success(true));
        when(client.describeCollection(any())).thenReturn(R.success(existingSchema(3,
                "study-agent-retrieval-benchmark;schema=1;dimension=3", false, 128, false)));
        when(client.loadCollection(any())).thenReturn(R.success(new RpcStatus("ok")));
        MilvusBenchmarkSchema schema = new MilvusBenchmarkSchema(client, properties);

        schema.requireReady();
        schema.requireReady();

        verify(client).loadCollection(any());
        verify(client, never()).createCollection(any(CreateCollectionParam.class));
    }

    @Test
    void rejectsIncompatibleDescriptionWithoutDroppingCollection() {
        assertIncompatible(existingSchema(3, "other-schema", false, 128, false));
    }

    @Test
    void rejectsIncompatibleDimensionWithoutDroppingCollection() {
        assertIncompatible(existingSchema(4,
                "study-agent-retrieval-benchmark;schema=1;dimension=3", false, 128, false));
    }

    @Test
    void rejectsAutoIdPrimaryKeyWithoutDroppingCollection() {
        assertIncompatible(existingSchema(3,
                "study-agent-retrieval-benchmark;schema=1;dimension=3", true, 128, false));
    }

    @Test
    void rejectsWrongVarcharLengthWithoutDroppingCollection() {
        assertIncompatible(existingSchema(3,
                "study-agent-retrieval-benchmark;schema=1;dimension=3", false, 64, false));
    }

    @Test
    void rejectsDynamicFieldsWithoutDroppingCollection() {
        assertIncompatible(existingSchema(3,
                "study-agent-retrieval-benchmark;schema=1;dimension=3", false, 128, true));
    }

    private void assertIncompatible(DescribeCollectionResponse description) {
        when(client.hasCollection(any())).thenReturn(R.success(true));
        when(client.describeCollection(any())).thenReturn(R.success(description));
        MilvusBenchmarkSchema schema = new MilvusBenchmarkSchema(client, properties);

        assertThatThrownBy(schema::requireReady)
                .isInstanceOf(BenchmarkOperationException.class)
                .hasMessage("BENCHMARK_SCHEMA_INCOMPATIBLE");
        verify(client, never()).dropCollection(any(DropCollectionParam.class));
        verify(client, never()).createCollection(any(CreateCollectionParam.class));
        verify(client, never()).loadCollection(any());
    }

    private DescribeCollectionResponse existingSchema(
            int dimension,
            String description,
            boolean primaryAutoId,
            int primaryMaxLength,
            boolean dynamicFields
    ) {
        List<FieldSchema> fields = List.of(
                field("benchmark_vector_id", DataType.VarChar, true, primaryAutoId,
                        "max_length", Integer.toString(primaryMaxLength)),
                field("dataset_id", DataType.VarChar, false, false, "max_length", "128"),
                field("dataset_version", DataType.VarChar, false, false, "max_length", "64"),
                field("chunk_id", DataType.VarChar, false, false, "max_length", "512"),
                field("content_hash", DataType.VarChar, false, false, "max_length", "64"),
                field("embedding_model_id", DataType.VarChar, false, false, "max_length", "128"),
                field("embedding", DataType.FloatVector, false, false, "dim", Integer.toString(dimension)));
        return DescribeCollectionResponse.newBuilder()
                .setCollectionName(properties.getCollectionName())
                .setSchema(CollectionSchema.newBuilder()
                        .setName(properties.getCollectionName())
                        .setDescription(description)
                        .setEnableDynamicField(dynamicFields)
                        .addAllFields(fields))
                .build();
    }

    private FieldSchema field(
            String name,
            DataType type,
            boolean primary,
            boolean autoId,
            String parameter,
            String value
    ) {
        FieldSchema.Builder builder = FieldSchema.newBuilder()
                .setName(name)
                .setDataType(type)
                .setIsPrimaryKey(primary)
                .setAutoID(autoId);
        if (parameter != null) {
            builder.addTypeParams(KeyValuePair.newBuilder().setKey(parameter).setValue(value));
        }
        return builder.build();
    }
}
