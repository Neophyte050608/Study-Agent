package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import java.util.List;
import java.util.Map;

/** Result of evaluating one query by exact corpus chunk identity. */
public record GoldSetRetrievalResult(
        String queryId,
        String question,
        String category,
        List<String> expectedChunkIds,
        Map<String, Integer> relevance,
        List<String> retrievedChunkIds,
        boolean hit,
        int firstRelevantRank
) {
    public GoldSetRetrievalResult {
        queryId = queryId == null ? "" : queryId;
        question = question == null ? "" : question;
        category = category == null ? "" : category;
        expectedChunkIds = expectedChunkIds == null ? List.of() : List.copyOf(expectedChunkIds);
        relevance = relevance == null ? Map.of() : Map.copyOf(relevance);
        retrievedChunkIds = retrievedChunkIds == null ? List.of() : List.copyOf(retrievedChunkIds);
    }
}
