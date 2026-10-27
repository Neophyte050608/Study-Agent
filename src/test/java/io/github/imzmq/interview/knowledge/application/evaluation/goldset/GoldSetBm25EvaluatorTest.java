package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import io.github.imzmq.interview.knowledge.application.indexing.RetrievalTokenizerService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GoldSetBm25EvaluatorTest {

    private final GoldSetBm25Evaluator evaluator =
            new GoldSetBm25Evaluator(new RetrievalTokenizerService(), new GoldSetMetricsCalculator());

    @Test
    void ranksCorpusChunksWithStandardBm25AndPreservesChunkIds() {
        List<GoldSetChunk> chunks = List.of(
                chunk("c-redis", "Redis memory cache memory performance"),
                chunk("c-mysql", "MySQL btree database index"),
                chunk("c-noise", "Frontend component rendering")
        );
        GoldSetCase evalCase = goldCase(
                "q1",
                "Redis memory cache",
                List.of("c-redis"),
                Map.of("c-redis", 3)
        );

        GoldSetEvaluationRun run = evaluator.run(List.of(evalCase), chunks);

        assertThat(run.topK()).isEqualTo(20);
        assertThat(run.results()).hasSize(1);
        assertThat(run.results().getFirst().retrievedChunkIds())
                .startsWith("c-redis")
                .doesNotContain("c-mysql", "c-noise");
        assertThat(run.results().getFirst().firstRelevantRank()).isEqualTo(1);
        assertThat(run.metrics().recallAt5()).isEqualTo(1.0D);
        assertThat(run.metrics().hitRateAt20()).isEqualTo(1.0D);
        assertThat(run.metrics().mrr()).isEqualTo(1.0D);
        assertThat(run.metrics().ndcgAt10()).isEqualTo(1.0D);
    }

    @Test
    void returnsOnlyPositiveScoreCandidatesAndRespectsTopK() {
        List<GoldSetChunk> chunks = List.of(
                chunk("c1", "redis memory cache"),
                chunk("c2", "redis memory persistence"),
                chunk("c3", "redis memory eviction"),
                chunk("noise", "mysql btree index")
        );

        List<String> ids = evaluator.search("redis memory", chunks, 2).stream()
                .map(GoldSetBm25Match::chunkId)
                .toList();

        assertThat(ids).hasSize(2).doesNotContain("noise");
    }

    @Test
    void usesDocumentFrequencyAndLengthNormalizationForRanking() {
        List<GoldSetChunk> chunks = List.of(
                chunk("focused", "rareterm cache"),
                chunk("long", "rareterm cache cache cache cache cache unrelated unrelated unrelated unrelated"),
                chunk("other", "common database index")
        );

        List<GoldSetBm25Match> matches = evaluator.search("rareterm cache", chunks, 20);

        assertThat(matches).extracting(GoldSetBm25Match::chunkId)
                .containsExactly("focused", "long");
        assertThat(matches.getFirst().score()).isGreaterThan(matches.get(1).score());
    }

    @Test
    void handlesEmptyCorpusQueriesAndCasesWithoutExternalDependencies() {
        assertThat(evaluator.search("redis", List.of(), 20)).isEmpty();
        assertThat(evaluator.search("  ", List.of(chunk("c1", "redis")), 20)).isEmpty();

        GoldSetEvaluationRun run = evaluator.run(List.of(), List.of(chunk("c1", "redis")));
        assertThat(run.results()).isEmpty();
        assertThat(run.metrics().totalCases()).isZero();
    }

    private GoldSetChunk chunk(String id, String content) {
        return new GoldSetChunk(id, "doc-" + id, 0, content, "", "test", "");
    }

    private GoldSetCase goldCase(
            String queryId,
            String question,
            List<String> groundTruth,
            Map<String, Integer> relevance
    ) {
        return new GoldSetCase(queryId, question, groundTruth, relevance, "", "", "test");
    }
}
