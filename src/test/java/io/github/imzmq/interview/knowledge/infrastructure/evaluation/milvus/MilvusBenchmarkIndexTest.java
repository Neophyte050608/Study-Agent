package io.github.imzmq.interview.knowledge.infrastructure.evaluation.milvus;

import io.github.imzmq.interview.config.knowledge.RetrievalBenchmarkProperties;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkDatasetIdentity;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkIndexMatch;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkOperationException;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetChunk;
import io.grpc.Status;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.FieldData;
import io.milvus.grpc.IDs;
import io.milvus.grpc.LongArray;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.ScalarField;
import io.milvus.grpc.SearchResultData;
import io.milvus.grpc.SearchResults;
import io.milvus.grpc.StringArray;
import io.milvus.param.R;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.dml.UpsertParam;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MilvusBenchmarkIndexTest {

    private final MilvusServiceClient client = mock(MilvusServiceClient.class);
    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    private final MilvusBenchmarkSchema schema = mock(MilvusBenchmarkSchema.class);
    private final RetrievalBenchmarkProperties properties = new RetrievalBenchmarkProperties();
    private MilvusBenchmarkIndex index;

    @BeforeEach
    void setUp() {
        properties.setCollectionName("study_agent_retrieval_benchmark");
        properties.setEmbeddingDimension(3);
        index = new MilvusBenchmarkIndex(client, embeddingModel, schema, properties);
    }

    @Test
    void embedsOneBatchAndUpsertsStableGenericFields() {
        when(embeddingModel.embed(List.of("first", "second"))).thenReturn(List.of(
                new float[]{1, 2, 3}, new float[]{4, 5, 6}));
        when(client.upsert(any())).thenReturn(R.success(MutationResult.getDefaultInstance()));

        index.upsertBatch(identity(), List.of(chunk("c1", "first"), chunk("c2", "second")));
        index.upsertBatch(identity(), List.of(chunk("c1", "first"), chunk("c2", "second")));

        verify(embeddingModel, org.mockito.Mockito.times(2)).embed(List.of("first", "second"));
        ArgumentCaptor<UpsertParam> requests = ArgumentCaptor.forClass(UpsertParam.class);
        verify(client, org.mockito.Mockito.times(2)).upsert(requests.capture());
        List<InsertParam.Field> fields = requests.getAllValues().getFirst().getFields();
        assertThat(fields).extracting(InsertParam.Field::getName).containsExactly(
                "benchmark_vector_id", "dataset_id", "dataset_version", "chunk_id",
                "content_hash", "embedding_model_id", "embedding");
        assertThat(values(fields, "chunk_id")).isEqualTo(List.of("c1", "c2"));
        assertThat(values(fields, "benchmark_vector_id"))
                .isEqualTo(values(requests.getAllValues().get(1).getFields(), "benchmark_vector_id"));
        assertThat(requests.getAllValues().getFirst().toString()).doesNotContain("first", "second");
        verify(schema, org.mockito.Mockito.times(2)).requireReady();
    }

    @Test
    void searchesWithCompleteIdentityFilterAndExactChunkIds() {
        when(embeddingModel.embed("query")).thenReturn(new float[]{1, 2, 3});
        when(client.search(any(SearchParam.class))).thenReturn(R.success(searchResults("c1", 0.91F)));

        List<BenchmarkIndexMatch> matches = index.search(identity(), "query", 20);

        ArgumentCaptor<SearchParam> request = ArgumentCaptor.forClass(SearchParam.class);
        verify(client).search(request.capture());
        assertThat(request.getValue().getExpr())
                .contains("dataset_id == \"generic-rag\"")
                .contains("dataset_version == \"" + "a".repeat(64) + "\"")
                .contains("embedding_model_id == \"embedding-3\"");
        assertThat(request.getValue().getTopK()).isEqualTo(20);
        assertThat(request.getValue().getOutFields()).containsExactly("chunk_id");
        assertThat(matches).containsExactly(new BenchmarkIndexMatch("c1", 0.91F));
    }

    @Test
    void cleanupUsesOnlyEscapedDatasetEquality() {
        when(client.delete(any(DeleteParam.class))).thenReturn(R.success(MutationResult.getDefaultInstance()));

        index.deleteDataset("generic-rag");

        ArgumentCaptor<DeleteParam> request = ArgumentCaptor.forClass(DeleteParam.class);
        verify(client).delete(request.capture());
        assertThat(request.getValue().getExpr()).isEqualTo("dataset_id == \"generic-rag\"");
    }

    @Test
    void rejectsInvalidEmbeddingBatchWithoutLeakingContent() {
        when(embeddingModel.embed(List.of("secret chunk body"))).thenReturn(List.of(new float[]{1, 2}));

        assertThatThrownBy(() -> index.upsertBatch(identity(), List.of(chunk("c1", "secret chunk body"))))
                .isInstanceOf(BenchmarkOperationException.class)
                .hasMessage("BENCHMARK_EMBEDDING_INVALID")
                .hasMessageNotContaining("secret chunk body");
    }

    @Test
    void rejectsInvalidSearchBeforeCallingMilvus() {
        assertThatThrownBy(() -> index.search(identity(), " ", 20))
                .isInstanceOf(BenchmarkOperationException.class)
                .hasMessage("BENCHMARK_QUERY_INVALID");
        assertThatThrownBy(() -> index.search(identity(), "query", 201))
                .isInstanceOf(BenchmarkOperationException.class)
                .hasMessage("BENCHMARK_TOP_K_INVALID");
    }

    @Test
    void mapsNullAndTimeoutResponsesToStableCodes() {
        when(embeddingModel.embed(List.of("content"))).thenReturn(List.of(new float[]{1, 2, 3}));
        when(client.upsert(any())).thenReturn(null);
        assertThatThrownBy(() -> index.upsertBatch(identity(), List.of(chunk("c1", "content"))))
                .isInstanceOf(BenchmarkOperationException.class)
                .hasMessage("BENCHMARK_MILVUS_SDK_ERROR");

        when(client.upsert(any())).thenReturn(R.failed(Status.DEADLINE_EXCEEDED.asRuntimeException()));
        assertThatThrownBy(() -> index.upsertBatch(identity(), List.of(chunk("c1", "content"))))
                .isInstanceOf(BenchmarkOperationException.class)
                .hasMessage("BENCHMARK_MILVUS_TIMEOUT");
    }

    private List<?> values(List<InsertParam.Field> fields, String name) {
        return fields.stream().filter(field -> name.equals(field.getName())).findFirst().orElseThrow().getValues();
    }

    private SearchResults searchResults(String chunkId, float score) {
        SearchResultData data = SearchResultData.newBuilder()
                .setNumQueries(1).setTopK(1).addTopks(1)
                .setIds(IDs.newBuilder().setStrId(StringArray.newBuilder().addData("vector-id")))
                .addScores(score).addOutputFields("chunk_id")
                .addFieldsData(stringField("chunk_id", chunkId)).build();
        return SearchResults.newBuilder().setResults(data).build();
    }

    private FieldData stringField(String name, String value) {
        return FieldData.newBuilder().setFieldName(name).setType(DataType.VarChar)
                .setScalars(ScalarField.newBuilder().setStringData(StringArray.newBuilder().addData(value)))
                .build();
    }

    private GoldSetChunk chunk(String id, String content) {
        return new GoldSetChunk(id, "", 0, content, "", "", "");
    }

    private BenchmarkDatasetIdentity identity() {
        return new BenchmarkDatasetIdentity("generic-rag", "a".repeat(64), "embedding-3", 3);
    }
}
