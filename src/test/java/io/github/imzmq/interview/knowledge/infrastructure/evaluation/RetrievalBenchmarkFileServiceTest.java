package io.github.imzmq.interview.knowledge.infrastructure.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.config.knowledge.RetrievalBenchmarkProperties;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkDatasetIdentity;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkIndex;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkIndexMatch;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkOperation;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkOperationException;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetChunk;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetComparisonMarkdownRenderer;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetMetricsCalculator;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetRunComparator;
import io.github.imzmq.interview.knowledge.application.indexing.RetrievalTokenizerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrievalBenchmarkFileServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void validateNeverTouchesIndexOrCheckpoint() throws Exception {
        Fixture fixture = fixture();

        RetrievalBenchmarkFileResult result = fixture.service().execute(BenchmarkOperation.VALIDATE);

        assertThat(result.operation()).isEqualTo(BenchmarkOperation.VALIDATE);
        assertThat(result.goldCaseCount()).isEqualTo(1);
        assertThat(result.corpusChunkCount()).isEqualTo(2);
        assertThat(fixture.index().calls).isEmpty();
        assertThat(fixture.properties().requireCheckpointPath()).doesNotExist();
    }

    @Test
    void cleanRequiresExactDatasetConfirmation() throws Exception {
        Fixture fixture = fixture();
        fixture.properties().setCleanupConfirmation("wrong");

        assertThatThrownBy(() -> fixture.service().execute(BenchmarkOperation.CLEAN))
                .isInstanceOf(BenchmarkOperationException.class)
                .hasMessage("BENCHMARK_CLEAN_CONFIRMATION_MISMATCH");
        assertThat(fixture.index().calls).isEmpty();
    }

    @Test
    void indexUsesCorpusFingerprintAndPersistsCompletedCheckpoint() throws Exception {
        Fixture fixture = fixture();
        fixture.index().count = 2;

        RetrievalBenchmarkFileResult result = fixture.service().execute(BenchmarkOperation.INDEX);

        assertThat(result.indexingResult()).isNotNull();
        assertThat(result.indexingResult().identity().datasetId()).isEqualTo("generic-rag");
        assertThat(result.indexingResult().identity().datasetVersion()).hasSize(64);
        assertThat(fixture.index().calls).containsExactly("upsert:2", "count");
        assertThat(fixture.properties().requireCheckpointPath()).exists();
    }

    @Test
    void successfulComparisonAtomicallyReplacesReportWithHonestLabels() throws Exception {
        Fixture fixture = fixture();
        fixture.index().count = 2;
        fixture.service().execute(BenchmarkOperation.INDEX);
        Path output = fixture.properties().requireOutputPath();
        Files.writeString(output, "old-report");

        RetrievalBenchmarkFileResult result = fixture.service().execute(BenchmarkOperation.COMPARE);

        assertThat(result.report()).isNotNull();
        assertThat(Files.readString(output))
                .contains("本报告比较隔离向量检索与本地 BM25，不代表完整生产混合检索链路。")
                .contains("Candidate: `isolated-vector/embedding-test`")
                .contains("Baseline: `bm25`")
                .doesNotContain("old-report", "Candidate: `production`");
        assertThat(output.resolveSibling(output.getFileName() + ".tmp")).doesNotExist();
    }

    @Test
    void failedComparisonDoesNotOverwriteExistingReport() throws Exception {
        Fixture fixture = fixture();
        Path output = fixture.properties().requireOutputPath();
        Files.writeString(output, "known-good");

        assertThatThrownBy(() -> fixture.service().execute(BenchmarkOperation.COMPARE))
                .isInstanceOf(BenchmarkOperationException.class)
                .hasMessage("BENCHMARK_INDEX_NOT_READY");
        assertThat(Files.readString(output)).isEqualTo("known-good");
    }

    private Fixture fixture() throws Exception {
        Path query = tempDir.resolve("queries.jsonl");
        Path corpus = tempDir.resolve("corpus.jsonl");
        Files.writeString(query, """
                {"query_id":"q1","question":"alpha","ground_truth":["c1"],"relevance":{"c1":3}}
                """);
        Files.writeString(corpus, """
                {"chunk_id":"c1","document_id":"d1","content":"alpha concept"}
                {"chunk_id":"c2","document_id":"d2","content":"beta concept"}
                """);
        RetrievalBenchmarkProperties properties = new RetrievalBenchmarkProperties();
        properties.setDatasetId("generic-rag");
        properties.setGoldFile(query.toString());
        properties.setCorpusFile(corpus.toString());
        properties.setOutputFile(tempDir.resolve("report.md").toString());
        properties.setCheckpointFile(tempDir.resolve("checkpoint.json").toString());
        properties.setCollectionName("generic_benchmark");
        properties.setEmbeddingModelId("embedding-test");
        properties.setEmbeddingDimension(3);
        properties.setBatchSize(64);
        properties.setCleanupConfirmation("generic-rag");
        FakeIndex index = new FakeIndex();
        GoldSetMetricsCalculator metrics = new GoldSetMetricsCalculator();
        RetrievalBenchmarkFileService service = new RetrievalBenchmarkFileService(
                properties,
                new BenchmarkDatasetFileLoader(),
                index,
                new ObjectMapper().findAndRegisterModules(),
                new RetrievalTokenizerService(),
                metrics,
                new GoldSetRunComparator(metrics),
                new GoldSetComparisonMarkdownRenderer(),
                new AtomicBenchmarkReportWriter());
        return new Fixture(properties, index, service);
    }

    private record Fixture(
            RetrievalBenchmarkProperties properties,
            FakeIndex index,
            RetrievalBenchmarkFileService service
    ) {
    }

    private static final class FakeIndex implements BenchmarkIndex {
        private final List<String> calls = new ArrayList<>();
        private long count;

        @Override
        public void upsertBatch(BenchmarkDatasetIdentity identity, List<GoldSetChunk> chunks) {
            calls.add("upsert:" + chunks.size());
        }

        @Override
        public List<BenchmarkIndexMatch> search(
                BenchmarkDatasetIdentity identity,
                String query,
                int topK
        ) {
            calls.add("search");
            return List.of(new BenchmarkIndexMatch("c1", 1F));
        }

        @Override
        public long count(BenchmarkDatasetIdentity identity) {
            calls.add("count");
            return count;
        }

        @Override
        public void deleteDataset(String datasetId) {
            calls.add("delete:" + datasetId);
        }
    }
}
