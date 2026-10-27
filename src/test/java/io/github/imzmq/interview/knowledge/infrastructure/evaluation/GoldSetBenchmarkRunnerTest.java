package io.github.imzmq.interview.knowledge.infrastructure.evaluation;

import io.github.imzmq.interview.config.knowledge.GoldSetBenchmarkProperties;
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
import org.springframework.boot.DefaultApplicationArguments;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GoldSetBenchmarkRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void usesConfiguredPathsWhenExplicitlyCreatedByConditionalConfiguration() throws Exception {
        Path goldFile = tempDir.resolve("gold.txt");
        Path corpusFile = tempDir.resolve("test.txt");
        Path outputFile = tempDir.resolve("report.md");
        Files.writeString(goldFile, """
                {"query_id":"q1","question":"Redis","ground_truth":["c1"],"relevance":{"c1":3}}
                """);
        Files.writeString(corpusFile, """
                {"chunk_id":"c1","document_id":"d1","content":"Redis"}
                """);
        GoldSetBenchmarkProperties properties = new GoldSetBenchmarkProperties();
        properties.setGoldFile(goldFile.toString());
        properties.setCorpusFile(corpusFile.toString());
        properties.setOutputFile(outputFile.toString());

        GoldSetBenchmarkRunner runner = new GoldSetBenchmarkRunner(properties, fileService("c1"));
        runner.run(new DefaultApplicationArguments(new String[0]));

        assertThat(outputFile).exists();
        assertThat(Files.readString(outputFile)).contains("| q1 |  | TIE |");
    }

    private GoldSetBenchmarkFileService fileService(String chunkId) {
        GoldSetMetricsCalculator metrics = new GoldSetMetricsCalculator();
        RAGService ragService = new StubRagService(new RAGService.KnowledgePacket(
                "Redis",
                List.of(new Document("Redis", Map.of("chunk_id", chunkId))),
                List.of(),
                "context",
                "",
                "",
                false
        ));
        return new GoldSetBenchmarkFileService(
                new GoldSetBenchmarkComparisonService(
                        new GoldSetRetrievalEvaluator(ragService, metrics),
                        new GoldSetBm25Evaluator(new RetrievalTokenizerService(), metrics),
                        new GoldSetRunComparator(metrics)
                ),
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
