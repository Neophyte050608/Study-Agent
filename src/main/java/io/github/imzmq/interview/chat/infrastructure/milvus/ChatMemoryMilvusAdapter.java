package io.github.imzmq.interview.chat.infrastructure.milvus;

import io.github.imzmq.interview.chat.application.memory.ChatMemoryProjectionException;
import io.github.imzmq.interview.chat.application.memory.ChatMemoryProperties;
import io.grpc.Status;
import io.milvus.client.MilvusClient;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.R;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.MetricType;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.QueryIteratorParam;
import io.milvus.param.dml.UpsertParam;
import io.milvus.grpc.SearchResults;
import io.milvus.grpc.QueryResults;
import io.milvus.response.SearchResultsWrapper;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.orm.iterator.QueryIterator;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "app.chat.memory.enabled", havingValue = "true")
public class ChatMemoryMilvusAdapter implements ChatMemoryProjectionPort {

    private final MilvusServiceClient rootClient;
    private final EmbeddingModel embeddingModel;
    private final ChatMemoryMilvusSchemaInitializer schemaInitializer;
    private final ChatMemoryProperties properties;

    public ChatMemoryMilvusAdapter(
            MilvusServiceClient rootClient,
            EmbeddingModel embeddingModel,
            ChatMemoryMilvusSchemaInitializer schemaInitializer,
            ChatMemoryProperties properties) {
        this.rootClient = rootClient;
        this.embeddingModel = embeddingModel;
        this.schemaInitializer = schemaInitializer;
        this.properties = properties;
    }

    @Override
    public void upsert(ChatMemoryProjection projection, Duration timeout) {
        Objects.requireNonNull(projection, "projection");
        schemaInitializer.requireReady();
        List<Float> vector = embed(projection.content());
        UpsertParam request = UpsertParam.newBuilder()
                .withCollectionName(properties.getCollectionName())
                .withFields(List.of(
                        field("projection_id", projection.projectionId()),
                        field("memory_id", projection.memoryId()),
                        field("user_id", projection.userId()),
                        field("memory_version", projection.memoryVersion()),
                        field("content_hash", projection.contentHash()),
                        field("category", projection.category()),
                        field("embedding_model_id", properties.getEmbeddingModelId()),
                        field("embedding", vector)))
                .build();
        assertMutation(deadlineClient(timeout).upsert(request));
    }

    @Override
    public List<ChatMemoryVectorCandidate> search(
            String userId, String query, int limit, Duration timeout) {
        schemaInitializer.requireReady();
        if (userId == null || userId.isBlank()) {
            throw new ChatMemoryProjectionException("MEMORY_PROJECTION_USER_INVALID");
        }
        if (query == null || query.isBlank()) {
            throw new ChatMemoryProjectionException("MEMORY_PROJECTION_QUERY_INVALID");
        }
        if (limit < 1 || limit > 200) {
            throw new ChatMemoryProjectionException("MEMORY_PROJECTION_LIMIT_INVALID");
        }
        SearchParam request = SearchParam.newBuilder()
                .withCollectionName(properties.getCollectionName())
                .withMetricType(MetricType.COSINE)
                .withVectorFieldName("embedding")
                .withFloatVectors(List.of(embed(query)))
                .withTopK(limit)
                .withExpr("user_id == \"" + escapeLiteral(userId) + "\"")
                .withOutFields(List.of(
                        "memory_id", "memory_version", "content_hash", "category"))
                .withParams("{\"ef\":64}")
                .build();
        SearchResults results = requiredData(deadlineClient(timeout).search(request));
        if (!results.hasResults()) {
            throw new ChatMemoryProjectionException("MEMORY_PROJECTION_RESPONSE_MISSING");
        }
        try {
            List<SearchResultsWrapper.IDScore> scores =
                    new SearchResultsWrapper(results.getResults()).getIDScore(0);
            List<ChatMemoryVectorCandidate> candidates = new ArrayList<>(scores.size());
            for (SearchResultsWrapper.IDScore score : scores) {
                candidates.add(new ChatMemoryVectorCandidate(
                        score.getStrID(),
                        requiredLong(score.get("memory_id")),
                        requiredLong(score.get("memory_version")),
                        requiredString(score.get("content_hash")),
                        requiredString(score.get("category")),
                        score.getScore()));
            }
            return List.copyOf(candidates);
        } catch (RuntimeException exception) {
            throw new ChatMemoryProjectionException("MEMORY_PROJECTION_RESPONSE_INVALID", exception);
        }
    }

    @Override
    public List<ChatMemoryProjectionRow> findByProjectionIds(
            List<String> projectionIds, Duration timeout) {
        schemaInitializer.requireReady();
        if (projectionIds == null || projectionIds.isEmpty()) {
            return List.of();
        }
        String expression = "projection_id in [" + projectionIds.stream()
                .map(this::quotedLiteral)
                .collect(java.util.stream.Collectors.joining(",")) + "]";
        QueryParam request = QueryParam.newBuilder()
                .withCollectionName(properties.getCollectionName())
                .withExpr(expression)
                .withOutFields(projectionFields())
                .withLimit((long) projectionIds.size())
                .build();
        QueryResults results = requiredData(deadlineClient(timeout).query(request));
        return toProjectionRows(new QueryResultsWrapper(results).getRowRecords());
    }

    @Override
    public ChatMemoryProjectionCursor openCursor(int pageSize, Duration timeout) {
        schemaInitializer.requireReady();
        if (pageSize < 1 || pageSize > 1_000) {
            throw new ChatMemoryProjectionException("MEMORY_PROJECTION_LIMIT_INVALID");
        }
        QueryIteratorParam request = QueryIteratorParam.newBuilder()
                .withCollectionName(properties.getCollectionName())
                .withExpr("")
                .withOutFields(projectionFields())
                .withBatchSize((long) pageSize)
                .build();
        QueryIterator iterator = requiredData(deadlineClient(timeout).queryIterator(request));
        return new ChatMemoryProjectionCursor() {
            @Override
            public List<ChatMemoryProjectionRow> nextPage() {
                try {
                    return toProjectionRows(iterator.next());
                } catch (RuntimeException exception) {
                    throw new ChatMemoryProjectionException(
                            "MEMORY_PROJECTION_RESPONSE_INVALID", exception);
                }
            }

            @Override
            public void close() {
                iterator.close();
            }
        };
    }

    @Override
    public void deleteVersion(String userId, Long memoryId, Long version, Duration timeout) {
        delete(userFilter(userId, memoryId) + " && memory_version == " + requiredPositive(version, "version"), timeout);
    }

    @Override
    public void deleteOtherVersions(
            String userId, Long memoryId, Long currentVersion, Duration timeout) {
        delete(userFilter(userId, memoryId) + " && memory_version != "
                + requiredPositive(currentVersion, "version"), timeout);
    }

    @Override
    public void deleteMemory(String userId, Long memoryId, Duration timeout) {
        delete(userFilter(userId, memoryId), timeout);
    }

    private List<String> projectionFields() {
        return List.of("projection_id", "memory_id", "user_id", "memory_version",
                "content_hash", "category");
    }

    private List<ChatMemoryProjectionRow> toProjectionRows(
            List<QueryResultsWrapper.RowRecord> records) {
        try {
            List<ChatMemoryProjectionRow> rows = new ArrayList<>(records.size());
            for (QueryResultsWrapper.RowRecord record : records) {
                rows.add(new ChatMemoryProjectionRow(
                        requiredString(record.get("projection_id")),
                        requiredLong(record.get("memory_id")),
                        requiredString(record.get("user_id")),
                        requiredLong(record.get("memory_version")),
                        requiredString(record.get("content_hash")),
                        requiredString(record.get("category"))));
            }
            return List.copyOf(rows);
        } catch (RuntimeException exception) {
            throw new ChatMemoryProjectionException("MEMORY_PROJECTION_RESPONSE_INVALID", exception);
        }
    }

    private String quotedLiteral(String value) {
        if (value == null || value.isBlank()) {
            throw new ChatMemoryProjectionException("MEMORY_PROJECTION_ID_INVALID");
        }
        return "\"" + escapeLiteral(value) + "\"";
    }

    private List<Float> embed(String text) {
        float[] embedding;
        try {
            embedding = embeddingModel.embed(text);
        } catch (RuntimeException exception) {
            throw new ChatMemoryProjectionException("MEMORY_PROJECTION_EMBEDDING_FAILED", exception);
        }
        if (embedding == null || embedding.length != properties.getEmbeddingDimension()) {
            throw new ChatMemoryProjectionException("MEMORY_PROJECTION_EMBEDDING_INVALID");
        }
        List<Float> vector = new ArrayList<>(embedding.length);
        for (float value : embedding) {
            vector.add(value);
        }
        return List.copyOf(vector);
    }

    private long requiredLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new ChatMemoryProjectionException("MEMORY_PROJECTION_RESPONSE_INVALID");
    }

    private String requiredString(Object value) {
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new ChatMemoryProjectionException("MEMORY_PROJECTION_RESPONSE_INVALID");
    }

    private void delete(String expression, Duration timeout) {
        schemaInitializer.requireReady();
        DeleteParam request = DeleteParam.newBuilder()
                .withCollectionName(properties.getCollectionName())
                .withExpr(expression)
                .build();
        assertMutation(deadlineClient(timeout).delete(request));
    }

    private String userFilter(String userId, Long memoryId) {
        if (userId == null || userId.isBlank()) {
            throw new ChatMemoryProjectionException("MEMORY_PROJECTION_USER_INVALID");
        }
        return "user_id == \"" + escapeLiteral(userId) + "\" && memory_id == "
                + requiredPositive(memoryId, "memoryId");
    }

    private String escapeLiteral(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private long requiredPositive(Long value, String name) {
        if (value == null || value < 1) {
            throw new ChatMemoryProjectionException("MEMORY_PROJECTION_" + name.toUpperCase() + "_INVALID");
        }
        return value;
    }

    private MilvusClient deadlineClient(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new ChatMemoryProjectionException("MEMORY_PROJECTION_TIMEOUT_INVALID");
        }
        return rootClient.withTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private InsertParam.Field field(String name, Object value) {
        return new InsertParam.Field(name, List.of(value));
    }

    private <T> T requiredData(R<T> response) {
        assertResponse(response);
        if (response.getData() == null) {
            throw new ChatMemoryProjectionException("MEMORY_PROJECTION_RESPONSE_MISSING");
        }
        return response.getData();
    }

    private void assertMutation(R<?> response) {
        assertResponse(response);
        if (response.getData() == null) {
            throw new ChatMemoryProjectionException("MEMORY_PROJECTION_RESPONSE_MISSING");
        }
    }

    private void assertResponse(R<?> response) {
        if (response == null) {
            throw new ChatMemoryProjectionException("MEMORY_PROJECTION_SDK_ERROR");
        }
        if (response.getException() != null) {
            String code = Status.fromThrowable(response.getException()).getCode()
                    == Status.Code.DEADLINE_EXCEEDED
                    ? "MEMORY_PROJECTION_TIMEOUT"
                    : "MEMORY_PROJECTION_SDK_ERROR";
            throw new ChatMemoryProjectionException(code, response.getException());
        }
        if (!Integer.valueOf(R.Status.Success.getCode()).equals(response.getStatus())) {
            throw new ChatMemoryProjectionException("MEMORY_PROJECTION_STATUS_FAILED");
        }
    }
}
