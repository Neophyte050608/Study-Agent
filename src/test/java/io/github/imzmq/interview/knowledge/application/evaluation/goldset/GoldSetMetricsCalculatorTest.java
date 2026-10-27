package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class GoldSetMetricsCalculatorTest {

    private final GoldSetMetricsCalculator calculator = new GoldSetMetricsCalculator();

    @Test
    void calculatesSaberRetrievalMetricsWithMacroAveraging() {
        GoldSetRetrievalResult first = result(
                "q1",
                List.of("a", "b"),
                Map.of("a", 3, "b", 1),
                List.of("x", "a", "b")
        );
        GoldSetRetrievalResult second = result(
                "q2",
                List.of("c"),
                Map.of("c", 2),
                List.of("x", "y")
        );

        GoldSetMetricsReport report = calculator.calculate(List.of(first, second));

        assertThat(report.totalCases()).isEqualTo(2);
        assertThat(report.recallAt5()).isEqualTo(0.5D);
        assertThat(report.recallAt10()).isEqualTo(0.5D);
        assertThat(report.recallAt20()).isEqualTo(0.5D);
        assertThat(report.hitRateAt5()).isEqualTo(0.5D);
        assertThat(report.hitRateAt10()).isEqualTo(0.5D);
        assertThat(report.hitRateAt20()).isEqualTo(0.5D);
        assertThat(report.mrr()).isEqualTo(0.25D);

        double expectedFirstNdcg = ((7.0D / log2(3)) + (1.0D / log2(4)))
                / ((7.0D / log2(2)) + (1.0D / log2(3)));
        assertThat(report.ndcgAt10()).isCloseTo(expectedFirstNdcg / 2.0D, offset(1.0e-12));
    }

    @Test
    void deduplicatesRetrievedChunkIdsAndKeepsTheirFirstRank() {
        GoldSetRetrievalResult result = result(
                "q1",
                List.of("target"),
                Map.of("target", 3),
                List.of("wrong", "wrong", "target", "target")
        );

        GoldSetMetricsReport report = calculator.calculate(List.of(result));

        assertThat(report.mrr()).isEqualTo(0.5D);
        assertThat(report.recallAt5()).isEqualTo(1.0D);
        assertThat(report.hitRateAt5()).isEqualTo(1.0D);
        assertThat(report.ndcgAt10()).isCloseTo(1.0D / log2(3), offset(1.0e-12));
    }

    @Test
    void treatsGroundTruthWithoutExplicitGradeAsBinaryRelevant() {
        GoldSetRetrievalResult result = result(
                "q1",
                List.of("target"),
                Map.of(),
                List.of("target")
        );

        GoldSetMetricsReport report = calculator.calculate(List.of(result));

        assertThat(report.recallAt5()).isEqualTo(1.0D);
        assertThat(report.ndcgAt10()).isEqualTo(1.0D);
    }

    @Test
    void returnsZeroMetricsForEmptyInput() {
        GoldSetMetricsReport report = calculator.calculate(List.of());

        assertThat(report.totalCases()).isZero();
        assertThat(report.recallAt5()).isZero();
        assertThat(report.hitRateAt20()).isZero();
        assertThat(report.mrr()).isZero();
        assertThat(report.ndcgAt10()).isZero();
    }

    private GoldSetRetrievalResult result(
            String queryId,
            List<String> expected,
            Map<String, Integer> relevance,
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
                "question",
                "category",
                expected,
                relevance,
                retrieved,
                firstRank > 0,
                firstRank
        );
    }

    private double log2(int value) {
        return Math.log(value) / Math.log(2.0D);
    }
}
