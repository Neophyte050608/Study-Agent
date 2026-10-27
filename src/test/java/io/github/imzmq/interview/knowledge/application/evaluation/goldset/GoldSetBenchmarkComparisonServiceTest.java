package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import io.github.imzmq.interview.knowledge.application.RAGService;
import io.github.imzmq.interview.knowledge.application.indexing.RetrievalTokenizerService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GoldSetBenchmarkComparisonServiceTest {

    @Test
    void comparesProductionRetrievalWithBm25OverTheSameGoldCases() {
        GoldSetMetricsCalculator metricsCalculator = new GoldSetMetricsCalculator();
        StubRagService ragService = new StubRagService(new RAGService.KnowledgePacket(
                "Redis persistence",
                List.of(new Document("redis persistence", Map.of("chunk_id", "c-production"))),
                List.of(),
                "context",
                "",
                "",
                false
        ));
        GoldSetBenchmarkComparisonService service = new GoldSetBenchmarkComparisonService(
                new GoldSetRetrievalEvaluator(ragService, metricsCalculator),
                new GoldSetBm25Evaluator(new RetrievalTokenizerService(), metricsCalculator),
                new GoldSetRunComparator(metricsCalculator)
        );
        GoldSetCase evalCase = new GoldSetCase(
                "q1",
                "Redis persistence",
                List.of("c-production"),
                Map.of("c-production", 3),
                "",
                "",
                "cache"
        );
        List<GoldSetChunk> corpus = List.of(
                new GoldSetChunk("c-production", "d1", 0, "unrelated frontend", "", "", ""),
                new GoldSetChunk("c-baseline", "d2", 0, "Redis persistence", "", "", "")
        );

        GoldSetComparisonReport report = service.compare(List.of(evalCase), corpus);

        assertThat(report.candidateLabel()).isEqualTo("production");
        assertThat(report.baselineLabel()).isEqualTo("bm25");
        assertThat(report.candidateWins()).isEqualTo(1);
        assertThat(report.baselineWins()).isZero();
        assertThat(report.cases()).extracting(GoldSetCaseComparison::queryId).containsExactly("q1");
        assertThat(ragService.lastTopK).isEqualTo(20);
        assertThat(ragService.lastAllowWebFallback).isFalse();
    }

    private static final class StubRagService extends RAGService {
        private final KnowledgePacket packet;
        private boolean lastAllowWebFallback = true;
        private int lastTopK = -1;

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
            this.lastAllowWebFallback = allowWebFallback;
            this.lastTopK = topK;
            return packet;
        }
    }
}
