package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compares two retrieval runs only when they contain exactly the same unique query IDs. */
@Component
public final class GoldSetRunComparator {

    private final GoldSetMetricsCalculator metricsCalculator;

    public GoldSetRunComparator(GoldSetMetricsCalculator metricsCalculator) {
        this.metricsCalculator = metricsCalculator;
    }

    public GoldSetComparisonReport compare(
            String candidateLabel,
            GoldSetEvaluationRun candidate,
            String baselineLabel,
            GoldSetEvaluationRun baseline
    ) {
        GoldSetEvaluationRun candidateRun = requireRun(candidate, "candidate");
        GoldSetEvaluationRun baselineRun = requireRun(baseline, "baseline");
        if (candidateRun.topK() != baselineRun.topK()) {
            throw new IllegalArgumentException("candidate and baseline must use the same topK");
        }
        Map<String, GoldSetRetrievalResult> candidateById = indexByQueryId(candidateRun.results(), "candidate");
        Map<String, GoldSetRetrievalResult> baselineById = indexByQueryId(baselineRun.results(), "baseline");
        if (!candidateById.keySet().equals(baselineById.keySet())) {
            throw new IllegalArgumentException("candidate and baseline must contain the same query IDs");
        }

        int candidateWins = 0;
        int baselineWins = 0;
        int ties = 0;
        java.util.ArrayList<GoldSetCaseComparison> comparisons = new java.util.ArrayList<>();
        for (Map.Entry<String, GoldSetRetrievalResult> entry : candidateById.entrySet()) {
            GoldSetRetrievalResult candidateResult = entry.getValue();
            GoldSetRetrievalResult baselineResult = baselineById.get(entry.getKey());
            requireSameGroundTruth(candidateResult, baselineResult);
            GoldSetMetricsReport candidateMetrics = metricsCalculator.calculate(List.of(candidateResult));
            GoldSetMetricsReport baselineMetrics = metricsCalculator.calculate(List.of(baselineResult));
            GoldSetComparisonOutcome outcome = compareMetrics(candidateMetrics, baselineMetrics);
            switch (outcome) {
                case CANDIDATE_WIN -> candidateWins++;
                case BASELINE_WIN -> baselineWins++;
                case TIE -> ties++;
            }
            comparisons.add(new GoldSetCaseComparison(
                    candidateResult.queryId(),
                    candidateResult.question(),
                    candidateResult.category(),
                    candidateMetrics,
                    baselineMetrics,
                    outcome
            ));
        }

        return new GoldSetComparisonReport(
                candidateLabel,
                baselineLabel,
                candidateRun.metrics(),
                baselineRun.metrics(),
                GoldSetMetricDelta.between(candidateRun.metrics(), baselineRun.metrics()),
                candidateWins,
                baselineWins,
                ties,
                comparisons
        );
    }

    private void requireSameGroundTruth(
            GoldSetRetrievalResult candidate,
            GoldSetRetrievalResult baseline
    ) {
        if (!candidate.expectedChunkIds().equals(baseline.expectedChunkIds())
                || !candidate.relevance().equals(baseline.relevance())) {
            throw new IllegalArgumentException(
                    "candidate and baseline must use the same ground truth for query ID: " + candidate.queryId()
            );
        }
    }

    private GoldSetComparisonOutcome compareMetrics(
            GoldSetMetricsReport candidate,
            GoldSetMetricsReport baseline
    ) {
        int order = Double.compare(candidate.ndcgAt10(), baseline.ndcgAt10());
        if (order == 0) {
            order = Double.compare(candidate.recallAt20(), baseline.recallAt20());
        }
        if (order == 0) {
            order = Double.compare(candidate.mrr(), baseline.mrr());
        }
        if (order > 0) {
            return GoldSetComparisonOutcome.CANDIDATE_WIN;
        }
        if (order < 0) {
            return GoldSetComparisonOutcome.BASELINE_WIN;
        }
        return GoldSetComparisonOutcome.TIE;
    }

    private GoldSetEvaluationRun requireRun(GoldSetEvaluationRun run, String label) {
        if (run == null) {
            throw new IllegalArgumentException(label + " run must not be null");
        }
        return run;
    }

    private Map<String, GoldSetRetrievalResult> indexByQueryId(
            List<GoldSetRetrievalResult> results,
            String label
    ) {
        Map<String, GoldSetRetrievalResult> indexed = new LinkedHashMap<>();
        if (results == null) {
            return indexed;
        }
        for (GoldSetRetrievalResult result : results) {
            if (result == null) {
                continue;
            }
            String queryId = result.queryId() == null ? "" : result.queryId().trim();
            if (queryId.isEmpty()) {
                throw new IllegalArgumentException(label + " run contains an empty query ID");
            }
            if (indexed.putIfAbsent(queryId, result) != null) {
                throw new IllegalArgumentException(label + " run contains duplicate query ID: " + queryId);
            }
        }
        return indexed;
    }
}
