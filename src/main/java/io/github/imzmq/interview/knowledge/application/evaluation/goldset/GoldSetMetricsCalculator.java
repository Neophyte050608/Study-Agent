package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Calculates Saber-compatible retrieval metrics from exact chunk identities. */
@Component
public final class GoldSetMetricsCalculator {

    private static final int[] RECALL_AND_HIT_CUTOFFS = {5, 10, 20};
    private static final int NDCG_CUTOFF = 10;

    public GoldSetMetricsReport calculate(List<GoldSetRetrievalResult> results) {
        List<GoldSetRetrievalResult> cases = results == null
                ? List.of()
                : results.stream().filter(item -> item != null).toList();
        if (cases.isEmpty()) {
            return GoldSetMetricsReport.empty();
        }

        double[] recallSums = new double[RECALL_AND_HIT_CUTOFFS.length];
        double[] hitSums = new double[RECALL_AND_HIT_CUTOFFS.length];
        double reciprocalRankSum = 0.0D;
        double ndcgSum = 0.0D;

        for (GoldSetRetrievalResult result : cases) {
            Set<String> expected = normalizedExpected(result.expectedChunkIds());
            List<String> retrieved = distinctRetrieved(result.retrievedChunkIds());
            for (int i = 0; i < RECALL_AND_HIT_CUTOFFS.length; i++) {
                int relevantRetrieved = relevantCountAt(retrieved, expected, RECALL_AND_HIT_CUTOFFS[i]);
                recallSums[i] += expected.isEmpty() ? 0.0D : (double) relevantRetrieved / expected.size();
                hitSums[i] += relevantRetrieved > 0 ? 1.0D : 0.0D;
            }
            int firstRelevantRank = firstRelevantRank(retrieved, expected);
            reciprocalRankSum += firstRelevantRank > 0 ? 1.0D / firstRelevantRank : 0.0D;
            ndcgSum += ndcgAt(retrieved, expected, result.relevance(), NDCG_CUTOFF);
        }

        int total = cases.size();
        return new GoldSetMetricsReport(
                total,
                recallSums[0] / total,
                recallSums[1] / total,
                recallSums[2] / total,
                hitSums[0] / total,
                hitSums[1] / total,
                hitSums[2] / total,
                reciprocalRankSum / total,
                ndcgSum / total
        );
    }

    private Set<String> normalizedExpected(List<String> expectedChunkIds) {
        LinkedHashSet<String> expected = new LinkedHashSet<>();
        if (expectedChunkIds == null) {
            return expected;
        }
        expectedChunkIds.stream()
                .map(this::normalizeId)
                .filter(item -> !item.isEmpty())
                .forEach(expected::add);
        return expected;
    }

    private List<String> distinctRetrieved(List<String> retrievedChunkIds) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (retrievedChunkIds != null) {
            retrievedChunkIds.stream()
                    .map(this::normalizeId)
                    .filter(item -> !item.isEmpty())
                    .forEach(unique::add);
        }
        return new ArrayList<>(unique);
    }

    private int relevantCountAt(List<String> retrieved, Set<String> expected, int cutoff) {
        int count = 0;
        int limit = Math.min(cutoff, retrieved.size());
        for (int i = 0; i < limit; i++) {
            if (expected.contains(retrieved.get(i))) {
                count++;
            }
        }
        return count;
    }

    private int firstRelevantRank(List<String> retrieved, Set<String> expected) {
        for (int i = 0; i < retrieved.size(); i++) {
            if (expected.contains(retrieved.get(i))) {
                return i + 1;
            }
        }
        return -1;
    }

    private double ndcgAt(
            List<String> retrieved,
            Set<String> expected,
            Map<String, Integer> relevance,
            int cutoff
    ) {
        if (expected.isEmpty()) {
            return 0.0D;
        }
        double dcg = 0.0D;
        int limit = Math.min(cutoff, retrieved.size());
        for (int i = 0; i < limit; i++) {
            String chunkId = retrieved.get(i);
            if (expected.contains(chunkId)) {
                dcg += gain(relevanceOf(chunkId, expected, relevance)) / log2(i + 2);
            }
        }

        List<Integer> idealGrades = expected.stream()
                .map(chunkId -> relevanceOf(chunkId, expected, relevance))
                .sorted(Comparator.reverseOrder())
                .limit(cutoff)
                .toList();
        double idealDcg = 0.0D;
        for (int i = 0; i < idealGrades.size(); i++) {
            idealDcg += gain(idealGrades.get(i)) / log2(i + 2);
        }
        return idealDcg == 0.0D ? 0.0D : dcg / idealDcg;
    }

    private int relevanceOf(String chunkId, Set<String> expected, Map<String, Integer> relevance) {
        if (!expected.contains(chunkId)) {
            return 0;
        }
        if (relevance == null) {
            return 1;
        }
        Integer grade = relevance.get(chunkId);
        return grade == null || grade <= 0 ? 1 : grade;
    }

    private double gain(int relevance) {
        return Math.pow(2.0D, relevance) - 1.0D;
    }

    private double log2(int value) {
        return Math.log(value) / Math.log(2.0D);
    }

    private String normalizeId(String value) {
        return value == null ? "" : value.trim();
    }
}
