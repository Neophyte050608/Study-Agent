package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import io.github.imzmq.interview.knowledge.application.RAGService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoldSetRetrievalEvaluatorTest {

    @Test
    void matchesOnlyByChunkIdAndReportsFirstRelevantRank() {
        GoldSetCase evalCase = new GoldSetCase(
                "q001",
                "远程 MCP 如何调用？",
                List.of("target-chunk", "secondary-chunk"),
                Map.of("target-chunk", 3, "secondary-chunk", 1),
                "article-1",
                "https://example.test/article-1",
                "人工智能"
        );
        Document sameTextWrongId = new Document(
                "wrong-document-id",
                "远程 MCP 如何调用？target-chunk",
                Map.of("chunk_id", "wrong-chunk")
        );
        Document exactId = new Document(
                "another-document-id",
                "unrelated text",
                Map.of("chunk_id", "target-chunk")
        );
        StubRagService ragService = new StubRagService(new RAGService.KnowledgePacket(
                evalCase.question(),
                List.of(sameTextWrongId, exactId),
                "context",
                "evidence",
                false
        ));
        GoldSetRetrievalEvaluator evaluator = new GoldSetRetrievalEvaluator(ragService, new GoldSetMetricsCalculator());

        GoldSetRetrievalResult result = evaluator.evaluate(evalCase, 5);

        assertThat(result.hit()).isTrue();
        assertThat(result.firstRelevantRank()).isEqualTo(2);
        assertThat(result.retrievedChunkIds()).containsExactly("wrong-chunk", "target-chunk");
        assertThat(result.expectedChunkIds()).containsExactly("target-chunk", "secondary-chunk");
        assertThat(ragService.lastQuestion).isEqualTo(evalCase.question());
        assertThat(ragService.lastAllowWebFallback).isFalse();
        assertThat(ragService.lastTopK).isEqualTo(5);
    }

    @Test
    void doesNotTreatKeywordOrContentSimilarityAsAHit() {
        GoldSetCase evalCase = new GoldSetCase(
                "q002",
                "Preact Hydration 是什么？",
                List.of("expected-chunk"),
                Map.of("expected-chunk", 3),
                "article-2",
                "https://example.test/article-2",
                "其他技术"
        );
        Document similarContent = new Document(
                "wrong-id",
                "expected-chunk Preact Hydration 会复用服务端渲染结果",
                Map.of("chunk_id", "wrong-chunk")
        );
        GoldSetRetrievalEvaluator evaluator = new GoldSetRetrievalEvaluator(new StubRagService(
                new RAGService.KnowledgePacket(
                        evalCase.question(),
                        List.of(similarContent),
                        "context",
                        "evidence",
                        false
                )
        ), new GoldSetMetricsCalculator());

        GoldSetRetrievalResult result = evaluator.evaluate(evalCase, 5);

        assertThat(result.hit()).isFalse();
        assertThat(result.firstRelevantRank()).isEqualTo(-1);
    }

    @Test
    void respectsTopKAndHandlesEmptyInputs() {
        GoldSetCase evalCase = new GoldSetCase(
                "q003", "Q", List.of("third"), Map.of("third", 3), "", "", "test");
        GoldSetRetrievalEvaluator evaluator = new GoldSetRetrievalEvaluator(new StubRagService(
                new RAGService.KnowledgePacket(
                        evalCase.question(),
                        List.of(
                                new Document("one", "one", Map.of("chunk_id", "one")),
                                new Document("two", "two", Map.of("chunk_id", "two")),
                                new Document("three", "three", Map.of("chunk_id", "third"))
                        ),
                        "context",
                        "evidence",
                        false
                )
        ), new GoldSetMetricsCalculator());

        assertThat(evaluator.evaluate(evalCase, 2).hit()).isFalse();
        assertThat(evaluator.evaluateAll(null, 5)).isEmpty();
        assertThat(evaluator.evaluateAll(List.of(), 5)).isEmpty();
    }

    @Test
    void deduplicatesChunkIdsBeforeComputingFirstRelevantRank() {
        GoldSetCase evalCase = new GoldSetCase(
                "q-duplicate", "Question", List.of("target"), Map.of("target", 3), "", "", "test");
        GoldSetRetrievalEvaluator evaluator = new GoldSetRetrievalEvaluator(
                new StubRagService(new RAGService.KnowledgePacket(
                        evalCase.question(),
                        List.of(
                                new Document("wrong-1", "wrong", Map.of("chunk_id", "wrong")),
                                new Document("wrong-2", "wrong again", Map.of("chunk_id", "wrong")),
                                new Document("target", "target", Map.of("chunk_id", "target"))
                        ),
                        "context",
                        "evidence",
                        false
                )),
                new GoldSetMetricsCalculator()
        );

        GoldSetRetrievalResult result = evaluator.evaluate(evalCase, 20);

        assertThat(result.retrievedChunkIds()).containsExactly("wrong", "target");
        assertThat(result.firstRelevantRank()).isEqualTo(2);
    }

    @Test
    void rejectsNonPositiveTopKBeforeCallingRetrieval() {
        GoldSetCase evalCase = new GoldSetCase(
                "q-invalid", "Question", List.of("target"), Map.of("target", 3), "", "", "test");
        StubRagService ragService = new StubRagService(null);
        GoldSetRetrievalEvaluator evaluator =
                new GoldSetRetrievalEvaluator(ragService, new GoldSetMetricsCalculator());

        assertThatThrownBy(() -> evaluator.evaluate(evalCase, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("topK must be greater than zero");
        assertThatThrownBy(() -> evaluator.evaluateAll(List.of(evalCase), -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("topK must be greater than zero");
        assertThat(ragService.lastTopK).isEqualTo(-1);
    }

    @Test
    void runsCompleteBenchmarkWithTopTwentyAndAggregatedMetrics() {
        GoldSetCase evalCase = new GoldSetCase(
                "q004", "Question", List.of("target"), Map.of("target", 3), "", "", "test");
        StubRagService ragService = new StubRagService(new RAGService.KnowledgePacket(
                evalCase.question(),
                List.of(new Document("doc", "content", Map.of("chunk_id", "target"))),
                "context",
                "evidence",
                false
        ));
        GoldSetRetrievalEvaluator evaluator = new GoldSetRetrievalEvaluator(ragService, new GoldSetMetricsCalculator());

        GoldSetEvaluationRun run = evaluator.run(List.of(evalCase));

        assertThat(run.topK()).isEqualTo(20);
        assertThat(run.metrics().totalCases()).isEqualTo(1);
        assertThat(run.metrics().recallAt20()).isEqualTo(1.0D);
        assertThat(run.metrics().hitRateAt20()).isEqualTo(1.0D);
        assertThat(run.metrics().ndcgAt10()).isEqualTo(1.0D);
        assertThat(run.results()).hasSize(1);
        assertThat(ragService.lastTopK).isEqualTo(20);
        assertThat(ragService.lastAllowWebFallback).isFalse();
    }

    private static final class StubRagService extends RAGService {
        private final KnowledgePacket packet;
        private String lastQuestion;
        private boolean lastAllowWebFallback = true;
        private int lastTopK = -1;

        private StubRagService(KnowledgePacket packet) {
            super(null, null, null, null, null, null, null);
            this.packet = packet;
        }

        @Override
        public KnowledgePacket buildKnowledgePacket(String question, String userAnswer, boolean allowWebFallback, int topK) {
            this.lastQuestion = question;
            this.lastAllowWebFallback = allowWebFallback;
            this.lastTopK = topK;
            return packet;
        }
    }
}
