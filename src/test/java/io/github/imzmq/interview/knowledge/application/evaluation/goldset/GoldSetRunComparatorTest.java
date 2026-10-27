package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class GoldSetRunComparatorTest {

    private final GoldSetMetricsCalculator metricsCalculator = new GoldSetMetricsCalculator();
    private final GoldSetRunComparator comparator = new GoldSetRunComparator(metricsCalculator);

    @Test
    void alignsCasesByQueryIdAndReportsWinsLossesAndMetricDeltas() {
        GoldSetRetrievalResult productionQ1 = result("q1", "Redis?", "cache", List.of("c1"), List.of("c1"));
        GoldSetRetrievalResult productionQ2 = result("q2", "MySQL?", "db", List.of("c2"), List.of("noise"));
        GoldSetRetrievalResult productionQ3 = result("q3", "JVM?", "jvm", List.of("c3"), List.of("c3"));
        GoldSetRetrievalResult baselineQ1 = result("q1", "Redis?", "cache", List.of("c1"), List.of("noise", "c1"));
        GoldSetRetrievalResult baselineQ2 = result("q2", "MySQL?", "db", List.of("c2"), List.of("c2"));
        GoldSetRetrievalResult baselineQ3 = result("q3", "JVM?", "jvm", List.of("c3"), List.of("c3"));

        GoldSetComparisonReport report = comparator.compare(
                "production",
                run(List.of(productionQ2, productionQ1, productionQ3)),
                "bm25",
                run(List.of(baselineQ3, baselineQ1, baselineQ2))
        );

        assertThat(report.candidateLabel()).isEqualTo("production");
        assertThat(report.baselineLabel()).isEqualTo("bm25");
        assertThat(report.candidateWins()).isEqualTo(1);
        assertThat(report.baselineWins()).isEqualTo(1);
        assertThat(report.ties()).isEqualTo(1);
        assertThat(report.cases()).extracting(GoldSetCaseComparison::queryId)
                .containsExactly("q2", "q1", "q3");
        assertThat(report.cases()).extracting(GoldSetCaseComparison::outcome)
                .containsExactly(
                        GoldSetComparisonOutcome.BASELINE_WIN,
                        GoldSetComparisonOutcome.CANDIDATE_WIN,
                        GoldSetComparisonOutcome.TIE
                );
        assertThat(report.metricDelta().hitRateAt5()).isCloseTo(-1.0D / 3.0D, within(1.0e-9));
        assertThat(report.metricDelta().mrr()).isCloseTo(-1.0D / 6.0D, within(1.0e-9));
        assertThat(report.cases().getFirst().candidateMetrics().mrr()).isZero();
        assertThat(report.cases().getFirst().baselineMetrics().mrr()).isEqualTo(1.0D);
    }

    @Test
    void rejectsDifferentCutoffsOrGroundTruthForTheSameQueryId() {
        GoldSetRetrievalResult candidateResult = result(
                "q1", "Redis?", "cache", List.of("c1"), List.of("c1")
        );
        GoldSetRetrievalResult differentGroundTruth = result(
                "q1", "Redis?", "cache", List.of("c2"), List.of("c2")
        );
        GoldSetEvaluationRun top20 = run(List.of(candidateResult));
        GoldSetEvaluationRun top10 = new GoldSetEvaluationRun(
                10,
                metricsCalculator.calculate(List.of(candidateResult)),
                List.of(candidateResult)
        );

        assertThatIllegalArgumentException()
                .isThrownBy(() -> comparator.compare("candidate", top20, "baseline", top10))
                .withMessageContaining("same topK");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> comparator.compare(
                        "candidate",
                        top20,
                        "baseline",
                        run(List.of(differentGroundTruth))
                ))
                .withMessageContaining("same ground truth")
                .withMessageContaining("q1");
    }

    @Test
    void rejectsMismatchedOrDuplicateQueryIdsInsteadOfComparingDifferentCaseSets() {
        GoldSetEvaluationRun candidate = run(List.of(
                result("q1", "one", "test", List.of("c1"), List.of("c1"))
        ));
        GoldSetEvaluationRun mismatched = run(List.of(
                result("q2", "two", "test", List.of("c2"), List.of("c2"))
        ));
        GoldSetEvaluationRun duplicated = run(List.of(
                result("q1", "one", "test", List.of("c1"), List.of("c1")),
                result("q1", "one again", "test", List.of("c1"), List.of("c1"))
        ));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> comparator.compare("candidate", candidate, "baseline", mismatched))
                .withMessageContaining("same query IDs");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> comparator.compare("candidate", duplicated, "baseline", candidate))
                .withMessageContaining("duplicate query ID");
    }

    private GoldSetEvaluationRun run(List<GoldSetRetrievalResult> results) {
        return new GoldSetEvaluationRun(20, metricsCalculator.calculate(results), results);
    }

    private GoldSetRetrievalResult result(
            String queryId,
            String question,
            String category,
            List<String> expected,
            List<String> retrieved
    ) {
        int firstRank = -1;
        for (int i = 0; i < retrieved.size(); i++) {
            if (expected.contains(retrieved.get(i))) {
                firstRank = i + 1;
                break;
            }
        }
        return new GoldSetRetrievalResult(
                queryId,
                question,
                category,
                expected,
                expected.stream().collect(java.util.stream.Collectors.toMap(id -> id, id -> 3)),
                retrieved,
                firstRank > 0,
                firstRank
        );
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
