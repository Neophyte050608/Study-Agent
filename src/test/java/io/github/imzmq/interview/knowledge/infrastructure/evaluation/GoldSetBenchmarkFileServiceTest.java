package io.github.imzmq.interview.knowledge.infrastructure.evaluation;

import io.github.imzmq.interview.knowledge.application.RAGService;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetBenchmarkComparisonService;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetBm25Evaluator;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetComparisonMarkdownRenderer;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetDataLoader;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetMetricsCalculator;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetRetrievalEvaluator;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetRunComparator;
import io.github.imzmq.interview.knowledge.application.indexing.RetrievalTokenizerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

class GoldSetBenchmarkFileServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsJsonlRunsBothRetrieversAndWritesUtf8Markdown() throws Exception {
        Path goldFile = tempDir.resolve("gold.txt");
        Path corpusFile = tempDir.resolve("test.txt");
        Path outputFile = tempDir.resolve("nested/retrieval-comparison.md");
        Files.writeString(goldFile, """
                {"query_id":"q1","question":"Redis persistence","ground_truth":["c-production"],"relevance":{"c-production":3},"category":"cache"}
                """);
        Files.writeString(corpusFile, """
                {"chunk_id":"c-production","document_id":"d1","content":"frontend rendering"}
                {"chunk_id":"c-baseline","document_id":"d2","content":"Redis persistence"}
                """);

        GoldSetBenchmarkFileService service = serviceWithProductionChunk("c-production");

        GoldSetBenchmarkFileResult result = service.run(goldFile, corpusFile, outputFile);

        assertThat(result.goldCaseCount()).isEqualTo(1);
        assertThat(result.corpusChunkCount()).isEqualTo(2);
        assertThat(result.outputFile()).isEqualTo(outputFile.toAbsolutePath().normalize());
        assertThat(result.report().candidateWins()).isEqualTo(1);
        assertThat(Files.readString(outputFile))
                .contains("# Retrieval Evaluation Comparison")
                .contains("Candidate wins: **1**, baseline wins: **0**, ties: **0**")
                .contains("| q1 | cache | CANDIDATE_WIN |");
    }

    @Test
    void rejectsMissingInputsBeforeCreatingOutput() {
        Path missingGold = tempDir.resolve("missing-gold.txt");
        Path corpusFile = tempDir.resolve("test.txt");
        Path outputFile = tempDir.resolve("report.md");

        assertThatIOException()
                .isThrownBy(() -> serviceWithProductionChunk("c1").run(missingGold, corpusFile, outputFile))
                .withMessageContaining("gold file");
        assertThat(outputFile).doesNotExist();
    }

    private GoldSetBenchmarkFileService serviceWithProductionChunk(String chunkId) {
        GoldSetMetricsCalculator metrics = new GoldSetMetricsCalculator();
        RAGService ragService = new StubRagService(new RAGService.KnowledgePacket(
                "Redis persistence",
                List.of(new Document("production evidence", Map.of("chunk_id", chunkId))),
                List.of(),
                "context",
                "",
                "",
                false
        ));
        GoldSetBenchmarkComparisonService comparisonService = new GoldSetBenchmarkComparisonService(
                new GoldSetRetrievalEvaluator(ragService, metrics),
                new GoldSetBm25Evaluator(new RetrievalTokenizerService(), metrics),
                new GoldSetRunComparator(metrics)
        );
        return new GoldSetBenchmarkFileService(
                comparisonService,
                new GoldSetComparisonMarkdownRenderer()
        );
    }

    private static final class StubRagService extends RAGService {
        private final KnowledgePacket packet;

        private StubRagService(KnowledgePacket packet) {
            super(null, null, null, null, null, null, null);
            this.packet = packet;
        }

        @Override
        public KnowledgePacket buildKnowledgePacket(
                String question,
                String userAnswer,
                boolean allowWebFallback,
                int topK
        ) {
            return packet;
        }
    }
}
