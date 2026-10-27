package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import io.github.imzmq.interview.knowledge.application.RAGService;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Executes chunk-level retrieval evaluation against the production local retrieval path.
 *
 * <p>Web fallback is always disabled. A hit requires exact equality between a retrieved
 * chunk identifier and a ground-truth chunk identifier; text or keyword similarity never
 * counts as a benchmark hit.</p>
 */
@Service
public final class GoldSetRetrievalEvaluator {

    public static final int BENCHMARK_TOP_K = 20;

    private final RAGService ragService;
    private final GoldSetMetricsCalculator metricsCalculator;

    public GoldSetRetrievalEvaluator(
            RAGService ragService,
            GoldSetMetricsCalculator metricsCalculator
    ) {
        this.ragService = ragService;
        this.metricsCalculator = metricsCalculator;
    }

    /** Runs one complete benchmark using Top-20 candidates for all required cutoffs. */
    public GoldSetEvaluationRun run(List<GoldSetCase> cases) {
        List<GoldSetRetrievalResult> results = evaluateAll(cases, BENCHMARK_TOP_K);
        return new GoldSetEvaluationRun(
                BENCHMARK_TOP_K,
                metricsCalculator.calculate(results),
                results
        );
    }

    public List<GoldSetRetrievalResult> evaluateAll(List<GoldSetCase> cases, int topK) {
        requirePositiveTopK(topK);
        if (cases == null || cases.isEmpty()) {
            return List.of();
        }
        return cases.stream()
                .filter(item -> item != null && item.question() != null && !item.question().isBlank())
                .map(item -> evaluate(item, topK))
                .toList();
    }

    public GoldSetRetrievalResult evaluate(GoldSetCase evalCase, int topK) {
        if (evalCase == null) {
            throw new IllegalArgumentException("evalCase must not be null");
        }
        requirePositiveTopK(topK);
        RAGService.KnowledgePacket packet = ragService.buildKnowledgePacket(evalCase.question(), "", false, topK);
        List<Document> documents = packet == null || packet.retrievedDocs() == null
                ? List.of()
                : packet.retrievedDocs();

        List<String> retrievedChunkIds = new ArrayList<>();
        Set<String> seenChunkIds = new LinkedHashSet<>();
        int firstRelevantRank = -1;
        int inspectedRank = 0;
        for (Document document : documents) {
            if (inspectedRank >= topK) {
                break;
            }
            String chunkId = RetrievedChunkIdentityResolver.resolve(document);
            if (chunkId.isEmpty() || !seenChunkIds.add(chunkId)) {
                continue;
            }
            inspectedRank++;
            retrievedChunkIds.add(chunkId);
            if (firstRelevantRank < 0 && evalCase.isRelevant(chunkId)) {
                firstRelevantRank = inspectedRank;
            }
        }

        return new GoldSetRetrievalResult(
                evalCase.queryId(),
                evalCase.question(),
                evalCase.category(),
                evalCase.groundTruth(),
                evalCase.relevance(),
                retrievedChunkIds,
                firstRelevantRank > 0,
                firstRelevantRank
        );
    }

    private void requirePositiveTopK(int topK) {
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be greater than zero");
        }
    }
}
