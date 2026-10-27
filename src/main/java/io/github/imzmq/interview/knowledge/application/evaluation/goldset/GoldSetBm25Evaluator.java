package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import io.github.imzmq.interview.knowledge.application.indexing.RetrievalTokenizerService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reproducible in-memory Okapi BM25 baseline for a chunk-level benchmark corpus.
 *
 * <p>The implementation builds one inverted index per benchmark run and does not use
 * MySQL, Milvus, a model provider, or an API key. It reuses the production retrieval
 * tokenizer so Chinese and technical terms follow the same tokenization policy.</p>
 */
@Service
public final class GoldSetBm25Evaluator {

    static final double DEFAULT_K1 = 1.2D;
    static final double DEFAULT_B = 0.75D;

    private final RetrievalTokenizerService tokenizer;
    private final GoldSetMetricsCalculator metricsCalculator;

    public GoldSetBm25Evaluator(
            RetrievalTokenizerService tokenizer,
            GoldSetMetricsCalculator metricsCalculator
    ) {
        this.tokenizer = tokenizer;
        this.metricsCalculator = metricsCalculator;
    }

    /** Runs the complete BM25 baseline using enough results for Recall/HitRate@20. */
    public GoldSetEvaluationRun run(List<GoldSetCase> cases, List<GoldSetChunk> chunks) {
        CorpusIndex index = buildIndex(chunks);
        List<GoldSetRetrievalResult> results = new ArrayList<>();
        if (cases != null) {
            for (GoldSetCase evalCase : cases) {
                if (evalCase == null || evalCase.question() == null || evalCase.question().isBlank()) {
                    continue;
                }
                List<String> retrievedIds = search(evalCase.question(), index, GoldSetRetrievalEvaluator.BENCHMARK_TOP_K)
                        .stream()
                        .map(GoldSetBm25Match::chunkId)
                        .toList();
                int firstRelevantRank = firstRelevantRank(evalCase, retrievedIds);
                results.add(new GoldSetRetrievalResult(
                        evalCase.queryId(),
                        evalCase.question(),
                        evalCase.category(),
                        evalCase.groundTruth(),
                        evalCase.relevance(),
                        retrievedIds,
                        firstRelevantRank > 0,
                        firstRelevantRank
                ));
            }
        }
        return new GoldSetEvaluationRun(
                GoldSetRetrievalEvaluator.BENCHMARK_TOP_K,
                metricsCalculator.calculate(results),
                results
        );
    }

    /** Convenience entry for small or ad-hoc searches. Benchmark runs reuse one index. */
    public List<GoldSetBm25Match> search(String query, List<GoldSetChunk> chunks, int topK) {
        return search(query, buildIndex(chunks), topK);
    }

    private List<GoldSetBm25Match> search(String query, CorpusIndex index, int topK) {
        if (query == null || query.isBlank() || topK <= 0 || index.documents().isEmpty()) {
            return List.of();
        }
        Set<String> queryTerms = new LinkedHashSet<>(tokenizer.tokenize(query));
        if (queryTerms.isEmpty()) {
            return List.of();
        }

        Map<Integer, Double> scores = new HashMap<>();
        int documentCount = index.documents().size();
        for (String term : queryTerms) {
            Map<Integer, Integer> postings = index.postings().get(term);
            if (postings == null || postings.isEmpty()) {
                continue;
            }
            int documentFrequency = postings.size();
            double inverseDocumentFrequency = Math.log(
                    1.0D + (documentCount - documentFrequency + 0.5D) / (documentFrequency + 0.5D)
            );
            for (Map.Entry<Integer, Integer> posting : postings.entrySet()) {
                IndexedChunk document = index.documents().get(posting.getKey());
                double termFrequency = posting.getValue();
                double lengthNormalization = 1.0D - DEFAULT_B
                        + DEFAULT_B * document.length() / index.averageDocumentLength();
                double score = inverseDocumentFrequency
                        * (termFrequency * (DEFAULT_K1 + 1.0D))
                        / (termFrequency + DEFAULT_K1 * lengthNormalization);
                scores.merge(posting.getKey(), score, Double::sum);
            }
        }

        return scores.entrySet().stream()
                .filter(entry -> entry.getValue() > 0.0D)
                .map(entry -> new GoldSetBm25Match(
                        index.documents().get(entry.getKey()).chunkId(),
                        entry.getValue()
                ))
                .sorted(Comparator.comparingDouble(GoldSetBm25Match::score)
                        .reversed()
                        .thenComparing(GoldSetBm25Match::chunkId))
                .limit(topK)
                .toList();
    }

    private CorpusIndex buildIndex(List<GoldSetChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return CorpusIndex.empty();
        }
        List<IndexedChunk> documents = new ArrayList<>();
        Map<String, Map<Integer, Integer>> postings = new HashMap<>();
        long totalLength = 0L;
        for (GoldSetChunk chunk : chunks) {
            if (chunk == null || chunk.chunkId() == null || chunk.chunkId().isBlank()) {
                continue;
            }
            List<String> tokens = tokenizer.tokenize(chunk.content());
            Map<String, Integer> termFrequencies = new LinkedHashMap<>();
            for (String token : tokens) {
                termFrequencies.merge(token, 1, Integer::sum);
            }
            int documentIndex = documents.size();
            int documentLength = tokens.size();
            documents.add(new IndexedChunk(chunk.chunkId().trim(), documentLength));
            totalLength += documentLength;
            for (Map.Entry<String, Integer> termFrequency : termFrequencies.entrySet()) {
                postings.computeIfAbsent(termFrequency.getKey(), ignored -> new HashMap<>())
                        .put(documentIndex, termFrequency.getValue());
            }
        }
        if (documents.isEmpty()) {
            return CorpusIndex.empty();
        }
        double averageDocumentLength = Math.max(1.0D, (double) totalLength / documents.size());
        return new CorpusIndex(List.copyOf(documents), postings, averageDocumentLength);
    }

    private int firstRelevantRank(GoldSetCase evalCase, List<String> retrievedIds) {
        for (int i = 0; i < retrievedIds.size(); i++) {
            if (evalCase.isRelevant(retrievedIds.get(i))) {
                return i + 1;
            }
        }
        return -1;
    }

    private record IndexedChunk(String chunkId, int length) {
    }

    private record CorpusIndex(
            List<IndexedChunk> documents,
            Map<String, Map<Integer, Integer>> postings,
            double averageDocumentLength
    ) {
        private static CorpusIndex empty() {
            return new CorpusIndex(List.of(), Map.of(), 1.0D);
        }
    }
}
