package io.github.imzmq.interview.knowledge.infrastructure.evaluation.milvus;

import io.github.imzmq.interview.config.knowledge.RetrievalBenchmarkProperties;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkOperationException;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lazily creates or validates the isolated retrieval-benchmark collection. */
public final class MilvusBenchmarkSchema {

    public static final String SCHEMA_VERSION = "1";
    private static final String DESCRIPTION_PREFIX =
            "study-agent-retrieval-benchmark;schema=" + SCHEMA_VERSION + ";dimension=";

    private final MilvusServiceClient client;
    private final RetrievalBenchmarkProperties properties;
    private volatile boolean ready;

    public MilvusBenchmarkSchema(
            MilvusServiceClient client,
            RetrievalBenchmarkProperties properties
    ) {
        this.client = client;
        this.properties = properties;
    }

    public void requireReady() {
        if (ready) {
            return;
        }
        synchronized (this) {
            if (ready) {
                return;
            }
            initialize();
            ready = true;
        }
    }

    private void initialize() {
        String collectionName = properties.getCollectionName();
        boolean exists = requiredData(client.hasCollection(HasCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .build()));
        if (exists) {
            DescribeCollectionResponse description = requiredData(client.describeCollection(
                    DescribeCollectionParam.newBuilder()
                            .withCollectionName(collectionName)
                            .build()));
            if (!isCompatible(description)) {
                throw new BenchmarkOperationException("BENCHMARK_SCHEMA_INCOMPATIBLE");
            }
        } else {
            create(collectionName);
        }
        assertSuccess(client.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .withSyncLoad(true)
                .build()));
    }

    private void create(String collectionName) {
        assertSuccess(client.createCollection(CreateCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .withDescription(expectedDescription())
                .withEnableDynamicField(false)
                .withFieldTypes(expectedFields())
                .build()));
        assertSuccess(client.createIndex(CreateIndexParam.newBuilder()
                .withCollectionName(collectionName)
                .withFieldName("embedding")
                .withIndexName("idx_benchmark_embedding")
                .withIndexType(IndexType.HNSW)
                .withMetricType(MetricType.COSINE)
                .withExtraParam("{\"M\":16,\"efConstruction\":200}")
                .withSyncMode(true)
                .build()));
    }

    private List<FieldType> expectedFields() {
        return List.of(
                varchar("benchmark_vector_id", 128, true),
                varchar("dataset_id", 128, false),
                varchar("dataset_version", 64, false),
                varchar("chunk_id", 512, false),
                varchar("content_hash", 64, false),
                varchar("embedding_model_id", 128, false),
                FieldType.newBuilder()
                        .withName("embedding")
                        .withDataType(DataType.FloatVector)
                        .withDimension(properties.getEmbeddingDimension())
                        .build());
    }

    private FieldType varchar(String name, int maxLength, boolean primary) {
        return FieldType.newBuilder()
                .withName(name)
                .withDataType(DataType.VarChar)
                .withMaxLength(maxLength)
                .withPrimaryKey(primary)
                .withAutoID(false)
                .build();
    }

    private boolean isCompatible(DescribeCollectionResponse response) {
        if (response == null
                || response.getSchema() == null
                || !expectedDescription().equals(response.getSchema().getDescription())
                || response.getSchema().getEnableDynamicField()) {
            return false;
        }
        Map<String, FieldSchema> fields = new LinkedHashMap<>();
        response.getSchema().getFieldsList().forEach(field -> fields.put(field.getName(), field));
        return fields.size() == 7
                && hasVarchar(fields, "benchmark_vector_id", true, false, 128)
                && hasVarchar(fields, "dataset_id", false, false, 128)
                && hasVarchar(fields, "dataset_version", false, false, 64)
                && hasVarchar(fields, "chunk_id", false, false, 512)
                && hasVarchar(fields, "content_hash", false, false, 64)
                && hasVarchar(fields, "embedding_model_id", false, false, 128)
                && hasVector(fields, "embedding", properties.getEmbeddingDimension());
    }

    private boolean hasVarchar(
            Map<String, FieldSchema> fields,
            String name,
            boolean primary,
            boolean autoId,
            int maxLength
    ) {
        FieldSchema field = fields.get(name);
        return field != null
                && field.getDataType() == DataType.VarChar
                && field.getIsPrimaryKey() == primary
                && field.getAutoID() == autoId
                && Integer.toString(maxLength).equals(typeParameter(field, "max_length"));
    }

    private boolean hasVector(Map<String, FieldSchema> fields, String name, int dimension) {
        FieldSchema field = fields.get(name);
        return field != null
                && field.getDataType() == DataType.FloatVector
                && !field.getIsPrimaryKey()
                && Integer.toString(dimension).equals(typeParameter(field, "dim"));
    }

    private String typeParameter(FieldSchema field, String key) {
        return field.getTypeParamsList().stream()
                .filter(parameter -> key.equals(parameter.getKey()))
                .map(parameter -> parameter.getValue())
                .findFirst()
                .orElse(null);
    }

    private String expectedDescription() {
        return DESCRIPTION_PREFIX + properties.getEmbeddingDimension();
    }

    private <T> T requiredData(R<T> response) {
        assertSuccess(response);
        if (response.getData() == null) {
            throw new BenchmarkOperationException("BENCHMARK_MILVUS_RESPONSE_MISSING");
        }
        return response.getData();
    }

    private void assertSuccess(R<?> response) {
        if (response == null || response.getException() != null) {
            throw new BenchmarkOperationException(
                    "BENCHMARK_MILVUS_SDK_ERROR",
                    response == null ? null : response.getException());
        }
        if (!Integer.valueOf(R.Status.Success.getCode()).equals(response.getStatus())) {
            throw new BenchmarkOperationException("BENCHMARK_MILVUS_STATUS_FAILED");
        }
    }
}
