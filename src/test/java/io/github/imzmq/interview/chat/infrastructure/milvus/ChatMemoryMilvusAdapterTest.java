package io.github.imzmq.interview.chat.infrastructure.milvus;

import io.github.imzmq.interview.chat.application.memory.ChatMemoryProjectionException;
import io.github.imzmq.interview.chat.application.memory.ChatMemoryProperties;
import io.milvus.client.MilvusClient;
import io.milvus.client.MilvusServiceClient;
import io.grpc.Status;
import io.milvus.grpc.DataType;
import io.milvus.grpc.FieldData;
import io.milvus.grpc.IDs;
import io.milvus.grpc.LongArray;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.ScalarField;
import io.milvus.grpc.SearchResultData;
import io.milvus.grpc.SearchResults;
import io.milvus.grpc.QueryResults;
import io.milvus.grpc.StringArray;
import io.milvus.param.R;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.QueryIteratorParam;
import io.milvus.param.dml.UpsertParam;
import io.milvus.orm.iterator.QueryIterator;
import io.milvus.response.QueryResultsWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMemoryMilvusAdapterTest {

    private final MilvusServiceClient rootClient = mock(MilvusServiceClient.class);
    private final MilvusClient deadlineClient = mock(MilvusClient.class);
    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    private final ChatMemoryMilvusSchemaInitializer schema = mock(ChatMemoryMilvusSchemaInitializer.class);
    private final ChatMemoryProperties properties = new ChatMemoryProperties();
    private ChatMemoryMilvusAdapter adapter;

    @BeforeEach
    void setUp() {
        properties.setCollectionName("chat_memories");
        properties.setEmbeddingDimension(3);
        properties.setEmbeddingModelId("embedding-test");
        when(rootClient.withTimeout(1500, TimeUnit.MILLISECONDS)).thenReturn(deadlineClient);
        adapter = new ChatMemoryMilvusAdapter(rootClient, embeddingModel, schema, properties);
    }

    @Test
    void upsertUsesDeadlineAndStoresVectorMetadataWithoutRawContent() {
        when(embeddingModel.embed("secret raw content")).thenReturn(new float[]{1, 2, 3});
        when(deadlineClient.upsert(any())).thenReturn(R.success(MutationResult.getDefaultInstance()));

        adapter.upsert(new ChatMemoryProjection(
                "10:2", 10L, "user-1", 2L, "hash", "FACT", "secret raw content"),
                Duration.ofMillis(1500));

        ArgumentCaptor<UpsertParam> captor = ArgumentCaptor.forClass(UpsertParam.class);
        verify(deadlineClient).upsert(captor.capture());
        assertThat(captor.getValue().getFields())
                .extracting(InsertParam.Field::getName)
                .containsExactly("projection_id", "memory_id", "user_id", "memory_version",
                        "content_hash", "category", "embedding_model_id", "embedding")
                .doesNotContain("content");
        assertThat(captor.getValue().getFields().stream()
                .flatMap(field -> field.getValues().stream())
                .noneMatch("secret raw content"::equals)).isTrue();
        verify(schema).requireReady();
    }

    @Test
    void deleteFiltersAreTenantScopedAndEscapeStringLiterals() {
        when(deadlineClient.delete(any(DeleteParam.class))).thenReturn(R.success(MutationResult.getDefaultInstance()));

        adapter.deleteVersion("a\\b\"c", 10L, 2L, Duration.ofMillis(1500));
        adapter.deleteOtherVersions("a\\b\"c", 10L, 2L, Duration.ofMillis(1500));
        adapter.deleteMemory("a\\b\"c", 10L, Duration.ofMillis(1500));

        ArgumentCaptor<DeleteParam> captor = ArgumentCaptor.forClass(DeleteParam.class);
        verify(deadlineClient, org.mockito.Mockito.times(3)).delete(captor.capture());
        assertThat(captor.getAllValues().get(0).getExpr())
                .isEqualTo("user_id == \"a\\\\b\\\"c\" && memory_id == 10 && memory_version == 2");
        assertThat(captor.getAllValues().get(1).getExpr())
                .isEqualTo("user_id == \"a\\\\b\\\"c\" && memory_id == 10 && memory_version != 2");
        assertThat(captor.getAllValues().get(2).getExpr())
                .isEqualTo("user_id == \"a\\\\b\\\"c\" && memory_id == 10");
    }

    @Test
    void searchIsTenantScopedAndReturnsOnlyProjectionMetadata() {
        when(embeddingModel.embed("current question")).thenReturn(new float[]{1, 2, 3});
        SearchResultData data = SearchResultData.newBuilder()
                .setNumQueries(1)
                .setTopK(1)
                .addAllTopks(List.of(1L))
                .setIds(IDs.newBuilder().setStrId(StringArray.newBuilder().addData("10:2")))
                .addAllScores(List.of(0.91F))
                .addAllOutputFields(List.of(
                        "memory_id", "memory_version", "content_hash", "category"))
                .addFieldsData(longField("memory_id", 10L))
                .addFieldsData(longField("memory_version", 2L))
                .addFieldsData(stringField("content_hash", "hash"))
                .addFieldsData(stringField("category", "FACT"))
                .build();
        when(deadlineClient.search(any(SearchParam.class))).thenReturn(R.success(
                SearchResults.newBuilder().setResults(data).build()));

        List<ChatMemoryVectorCandidate> candidates = adapter.search(
                "a\\b\"c", "current question", 5, Duration.ofMillis(1500));

        ArgumentCaptor<SearchParam> captor = ArgumentCaptor.forClass(SearchParam.class);
        verify(deadlineClient).search(captor.capture());
        assertThat(captor.getValue().getExpr()).isEqualTo("user_id == \"a\\\\b\\\"c\"");
        assertThat(captor.getValue().getOutFields())
                .containsExactly("memory_id", "memory_version", "content_hash", "category");
        assertThat(candidates).containsExactly(new ChatMemoryVectorCandidate(
                "10:2", 10L, 2L, "hash", "FACT", 0.91F));
    }

    @Test
    void projectionMetadataCanBeFetchedByEscapedImmutableIds() {
        QueryResults data = projectionQueryResults();
        when(deadlineClient.query(any(QueryParam.class))).thenReturn(R.success(data));

        List<ChatMemoryProjectionRow> rows = adapter.findByProjectionIds(
                List.of("10:2", "unsafe\"id"), Duration.ofMillis(1500));

        ArgumentCaptor<QueryParam> captor = ArgumentCaptor.forClass(QueryParam.class);
        verify(deadlineClient).query(captor.capture());
        assertThat(captor.getValue().getExpr())
                .isEqualTo("projection_id in [\"10:2\",\"unsafe\\\"id\"]");
        assertThat(captor.getValue().getOutFields())
                .containsExactly("projection_id", "memory_id", "user_id",
                        "memory_version", "content_hash", "category");
        assertThat(rows).containsExactly(new ChatMemoryProjectionRow(
                "10:2", 10L, "user-1", 2L, "hash", "FACT"));
    }

    @Test
    void projectionCursorUsesBoundedSdkIteratorPages() {
        QueryIterator iterator = mock(QueryIterator.class);
        List<QueryResultsWrapper.RowRecord> records =
                new QueryResultsWrapper(projectionQueryResults()).getRowRecords();
        when(deadlineClient.queryIterator(any(QueryIteratorParam.class)))
                .thenReturn(R.success(iterator));
        when(iterator.next()).thenReturn(records, List.of());

        try (ChatMemoryProjectionCursor cursor =
                     adapter.openCursor(200, Duration.ofMillis(1500))) {
            assertThat(cursor.nextPage()).containsExactly(new ChatMemoryProjectionRow(
                    "10:2", 10L, "user-1", 2L, "hash", "FACT"));
            assertThat(cursor.nextPage()).isEmpty();
        }

        ArgumentCaptor<QueryIteratorParam> captor =
                ArgumentCaptor.forClass(QueryIteratorParam.class);
        verify(deadlineClient).queryIterator(captor.capture());
        assertThat(captor.getValue().getBatchSize()).isEqualTo(200);
        assertThat(captor.getValue().getOutFields())
                .containsExactly("projection_id", "memory_id", "user_id",
                        "memory_version", "content_hash", "category");
        verify(iterator).close();
    }

    @Test
    void searchRejectsMissingMilvusDataAndInvalidEmbedding() {
        when(embeddingModel.embed("question")).thenReturn(new float[]{1, 2});
        assertThatThrownBy(() -> adapter.search(
                "user-1", "question", 5, Duration.ofMillis(1500)))
                .isInstanceOf(ChatMemoryProjectionException.class)
                .hasMessage("MEMORY_PROJECTION_EMBEDDING_INVALID");

        when(embeddingModel.embed("question")).thenReturn(new float[]{1, 2, 3});
        when(deadlineClient.search(any(SearchParam.class))).thenReturn(R.success());
        assertThatThrownBy(() -> adapter.search(
                "user-1", "question", 5, Duration.ofMillis(1500)))
                .isInstanceOf(ChatMemoryProjectionException.class)
                .hasMessage("MEMORY_PROJECTION_RESPONSE_MISSING");
    }

    private QueryResults projectionQueryResults() {
        return QueryResults.newBuilder()
                .addAllOutputFields(List.of(
                        "projection_id", "memory_id", "user_id", "memory_version",
                        "content_hash", "category"))
                .addFieldsData(stringField("projection_id", "10:2"))
                .addFieldsData(longField("memory_id", 10L))
                .addFieldsData(stringField("user_id", "user-1"))
                .addFieldsData(longField("memory_version", 2L))
                .addFieldsData(stringField("content_hash", "hash"))
                .addFieldsData(stringField("category", "FACT"))
                .build();
    }

    private FieldData longField(String name, long value) {
        return FieldData.newBuilder()
                .setFieldName(name)
                .setType(DataType.Int64)
                .setScalars(ScalarField.newBuilder()
                        .setLongData(LongArray.newBuilder().addData(value)))
                .build();
    }

    private FieldData stringField(String name, String value) {
        return FieldData.newBuilder()
                .setFieldName(name)
                .setType(DataType.VarChar)
                .setScalars(ScalarField.newBuilder()
                        .setStringData(StringArray.newBuilder().addData(value)))
                .build();
    }

    @Test
    void deadlineExceededIsReportedAsProjectionTimeout() {
        when(embeddingModel.embed("content")).thenReturn(new float[]{1, 2, 3});
        when(deadlineClient.upsert(any())).thenReturn(
                R.failed(Status.DEADLINE_EXCEEDED.asRuntimeException()));

        assertThatThrownBy(() -> adapter.upsert(new ChatMemoryProjection(
                "10:2", 10L, "user-1", 2L, "hash", "FACT", "content"),
                Duration.ofMillis(1500)))
                .isInstanceOf(ChatMemoryProjectionException.class)
                .hasMessage("MEMORY_PROJECTION_TIMEOUT");
    }

    @Test
    void sdkExceptionBusinessFailureAndMissingDataAreControlledFailures() {
        when(embeddingModel.embed("content")).thenReturn(new float[]{1, 2, 3});
        ChatMemoryProjection projection = new ChatMemoryProjection(
                "10:2", 10L, "user-1", 2L, "hash", "FACT", "content");

        R<MutationResult> exception = R.failed(new RuntimeException("transport"));
        when(deadlineClient.upsert(any())).thenReturn(exception);
        assertThatThrownBy(() -> adapter.upsert(projection, Duration.ofMillis(1500)))
                .isInstanceOf(ChatMemoryProjectionException.class)
                .hasMessage("MEMORY_PROJECTION_SDK_ERROR");

        R<MutationResult> businessFailure = new R<>();
        businessFailure.setStatus(R.Status.UnexpectedError.getCode());
        businessFailure.setData(MutationResult.getDefaultInstance());
        when(deadlineClient.upsert(any())).thenReturn(businessFailure);
        assertThatThrownBy(() -> adapter.upsert(projection, Duration.ofMillis(1500)))
                .isInstanceOf(ChatMemoryProjectionException.class)
                .hasMessage("MEMORY_PROJECTION_STATUS_FAILED");

        when(deadlineClient.upsert(any())).thenReturn(R.success());
        assertThatThrownBy(() -> adapter.upsert(projection, Duration.ofMillis(1500)))
                .isInstanceOf(ChatMemoryProjectionException.class)
                .hasMessage("MEMORY_PROJECTION_RESPONSE_MISSING");
    }
}
