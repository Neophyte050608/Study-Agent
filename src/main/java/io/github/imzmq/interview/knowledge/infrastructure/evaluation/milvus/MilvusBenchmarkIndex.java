package io.github.imzmq.interview.knowledge.infrastructure.evaluation.milvus;

import io.github.imzmq.interview.config.knowledge.RetrievalBenchmarkProperties;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkDatasetIdentity;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkFingerprint;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkIndex;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkIndexMatch;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkOperationException;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetChunk;
import io.grpc.Status;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.QueryResults;
import io.milvus.grpc.SearchResults;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.dml.UpsertParam;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;
import org.springframework.ai.embedding.EmbeddingModel;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Milvus adapter for the isolated benchmark index only. */
public final class MilvusBenchmarkIndex implements BenchmarkIndex {

    private static final int MAX_TOP_K = 200;

    private final MilvusServiceClient client;
    private final EmbeddingModel embeddingModel;
    private final MilvusBenchmarkSchema schema;
    private final RetrievalBenchmarkProperties properties;

    public MilvusBenchmarkIndex(
            MilvusServiceClient client,
            EmbeddingModel embeddingModel,
            MilvusBenchmarkSchema schema,
            RetrievalBenchmarkProperties properties
    ) {
        this.client = client;
        this.embeddingModel = embeddingModel;
        this.schema = schema;
        this.properties = properties;
    }

    @Override
    public void upsertBatch(BenchmarkDatasetIdentity identity, List<GoldSetChunk> chunks) {
        schema.requireReady();
        if (chunks == null || chunks.isEmpty()) {
            throw new BenchmarkOperationException("BENCHMARK_CHUNK_BATCH_INVALID");
        }
        List<String> contents = chunks.stream().map(GoldSetChunk::content).toList();
        List<float[]> embeddings = embedBatch(contents);
        List<String> vectorIds = new ArrayList<>(chunks.size());
        List<String> datasetIds = new ArrayList<>(chunks.size());
        List<String> versions = new ArrayList<>(chunks.size());
        List<String> chunkIds = new ArrayList<>(chunks.size());
        List<String> contentHashes = new ArrayList<>(chunks.size());
        List<String> modelIds = new ArrayList<>(chunks.size());
        List<List<Float>> vectors = new ArrayList<>(chunks.size());
        for (int position = 0; position < chunks.size(); position++) {
            GoldSetChunk chunk = chunks.get(position);
            vectorIds.add(vectorId(identity, chunk.chunkId()));
            datasetIds.add(identity.datasetId());
            versions.add(identity.datasetVersion());
            chunkIds.add(chunk.chunkId());
            contentHashes.add(BenchmarkFingerprint.sha256(chunk.content().getBytes(StandardCharsets.UTF_8)));
            modelIds.add(identity.embeddingModelId());
            vectors.add(toVector(embeddings.get(position)));
        }
        UpsertParam request = UpsertParam.newBuilder()
                .withCollectionName(properties.getCollectionName())
                .withFields(List.of(
                        field("benchmark_vector_id", vectorIds),
                        field("dataset_id", datasetIds),
                        field("dataset_version", versions),
                        field("chunk_id", chunkIds),
                        field("content_hash", contentHashes),
                        field("embedding_model_id", modelIds),
                        field("embedding", vectors)))
                .build();
        requiredMutation(client.upsert(request));
    }

    @Override
    public List<BenchmarkIndexMatch> search(
            BenchmarkDatasetIdentity identity,
            String query,
            int topK
    ) {
        if (query == null || query.isBlank()) {
            throw new BenchmarkOperationException("BENCHMARK_QUERY_INVALID");
        }
        if (topK < 1 || topK > MAX_TOP_K) {
            throw new BenchmarkOperationException("BENCHMARK_TOP_K_INVALID");
        }
        schema.requireReady();
        List<Float> vector = toVector(embedOne(query));
        SearchParam request = SearchParam.newBuilder()
                .withCollectionName(properties.getCollectionName())
                .withMetricType(MetricType.COSINE)
                .withVectorFieldName("embedding")
                .withFloatVectors(List.of(vector))
                .withTopK(topK)
                .withExpr(identityFilter(identity))
                .withOutFields(List.of("chunk_id"))
                .withParams("{\"ef\":64}")
                .build();
        SearchResults response = requiredData(client.search(request));
        if (!response.hasResults()) {
            throw new BenchmarkOperationException("BENCHMARK_MILVUS_RESPONSE_MISSING");
        }
        try {
            List<SearchResultsWrapper.IDScore> scores =
                    new SearchResultsWrapper(response.getResults()).getIDScore(0);
            List<BenchmarkIndexMatch> matches = new ArrayList<>(scores.size());
            for (SearchResultsWrapper.IDScore score : scores) {
                Object value = score.get("chunk_id");
                if (!(value instanceof String chunkId) || chunkId.isBlank()) {
                    throw new BenchmarkOperationException("BENCHMARK_MILVUS_RESPONSE_INVALID");
                }
                matches.add(new BenchmarkIndexMatch(chunkId, score.getScore()));
            }
            return List.copyOf(matches);
        } catch (BenchmarkOperationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BenchmarkOperationException("BENCHMARK_MILVUS_RESPONSE_INVALID", exception);
        }
    }

    @Override
    public long count(BenchmarkDatasetIdentity identity) {
        schema.requireReady();
        QueryParam request = QueryParam.newBuilder()
                .withCollectionName(properties.getCollectionName())
                .withExpr(identityFilter(identity))
                .withOutFields(List.of("count(*)"))
                .build();
        QueryResults response = requiredData(client.query(request));
        try {
            List<QueryResultsWrapper.RowRecord> rows =
                    new QueryResultsWrapper(response).getRowRecords();
            if (rows.size() != 1 || !(rows.getFirst().get("count(*)") instanceof Number number)) {
                throw new BenchmarkOperationException("BENCHMARK_MILVUS_RESPONSE_INVALID");
            }
            return number.longValue();
        } catch (BenchmarkOperationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BenchmarkOperationException("BENCHMARK_MILVUS_RESPONSE_INVALID", exception);
        }
    }

    @Override
    public void deleteDataset(String datasetId) {
        if (datasetId == null || datasetId.isBlank()) {
            throw new BenchmarkOperationException("BENCHMARK_DATASET_ID_INVALID");
        }
        schema.requireReady();
        DeleteParam request = DeleteParam.newBuilder()
                .withCollectionName(properties.getCollectionName())
                .withExpr("dataset_id == " + quoted(datasetId.trim()))
                .build();
        requiredMutation(client.delete(request));
    }

    private List<float[]> embedBatch(List<String> contents) {
        List<float[]> embeddings;
        try {
            embeddings = embeddingModel.embed(contents);
        } catch (RuntimeException exception) {
            throw new BenchmarkOperationException("BENCHMARK_EMBEDDING_FAILED", exception);
        }
        if (embeddings == null || embeddings.size() != contents.size()) {
            throw new BenchmarkOperationException("BENCHMARK_EMBEDDING_INVALID");
        }
        embeddings.forEach(this::validateEmbedding);
        return embeddings;
    }

    private float[] embedOne(String query) {
        float[] embedding;
        try {
            embedding = embeddingModel.embed(query);
        } catch (RuntimeException exception) {
            throw new BenchmarkOperationException("BENCHMARK_EMBEDDING_FAILED", exception);
        }
        validateEmbedding(embedding);
        return embedding;
    }

    private void validateEmbedding(float[] embedding) {
        if (embedding == null || embedding.length != properties.getEmbeddingDimension()) {
            throw new BenchmarkOperationException("BENCHMARK_EMBEDDING_INVALID");
        }
    }

    private List<Float> toVector(float[] embedding) {
        List<Float> vector = new ArrayList<>(embedding.length);
        for (float value : embedding) {
            vector.add(value);
        }
        return List.copyOf(vector);
    }

    private String vectorId(BenchmarkDatasetIdentity identity, String chunkId) {
        String material = identity.datasetId() + "\n"
                + identity.datasetVersion() + "\n"
                + identity.embeddingModelId() + "\n"
                + chunkId;
        return BenchmarkFingerprint.sha256(material.getBytes(StandardCharsets.UTF_8));
    }

    private String identityFilter(BenchmarkDatasetIdentity identity) {
        return "dataset_id == " + quoted(identity.datasetId())
                + " && dataset_version == " + quoted(identity.datasetVersion())
                + " && embedding_model_id == " + quoted(identity.embeddingModelId());
    }

    private String quoted(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private InsertParam.Field field(String name, List<?> values) {
        return new InsertParam.Field(name, values);
    }

    private <T> T requiredData(R<T> response) {
        assertResponse(response);
        if (response.getData() == null) {
            throw new BenchmarkOperationException("BENCHMARK_MILVUS_RESPONSE_MISSING");
        }
        return response.getData();
    }

    private void requiredMutation(R<MutationResult> response) {
        requiredData(response);
    }

    private void assertResponse(R<?> response) {
        if (response == null) {
            throw new BenchmarkOperationException("BENCHMARK_MILVUS_SDK_ERROR");
        }
        if (response.getException() != null) {
            String code = Status.fromThrowable(response.getException()).getCode()
                    == Status.Code.DEADLINE_EXCEEDED
                    ? "BENCHMARK_MILVUS_TIMEOUT"
                    : "BENCHMARK_MILVUS_SDK_ERROR";
            throw new BenchmarkOperationException(code, response.getException());
        }
        if (!Integer.valueOf(R.Status.Success.getCode()).equals(response.getStatus())) {
            throw new BenchmarkOperationException("BENCHMARK_MILVUS_STATUS_FAILED");
        }
    }
}
