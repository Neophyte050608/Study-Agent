package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import io.github.imzmq.interview.knowledge.application.indexing.RetrievalTokenizerService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IsolatedVectorBenchmarkServiceTest {

    @Test
    void comparesIsolatedVectorWithBm25UsingExactDeduplicatedChunkIds() {
        FakeIndex index = new FakeIndex(3, Map.of("Redis?", List.of(
                new BenchmarkIndexMatch("c-vector", 0.91),
                new BenchmarkIndexMatch("duplicate", 0.80),
                new BenchmarkIndexMatch("duplicate", 0.70))));
        IsolatedVectorBenchmarkService service = service(index, completedCheckpoint());

        IsolatedBenchmarkReport report = service.compare(identity(), cases(), corpus(), metadata());

        assertThat(report.comparison().candidateLabel()).isEqualTo("isolated-vector/embedding-3");
        assertThat(report.comparison().baselineLabel()).isEqualTo("bm25");
        assertThat(report.comparison().cases().getFirst().candidateMetrics().mrr()).isEqualTo(1.0);
        assertThat(index.requestedTopK).containsExactly(20);
        assertThat(index.queries).containsExactly("Redis?");
    }

    @Test
    void refusesNonCompletedCheckpointBeforeSearching() {
        BenchmarkCheckpoint incomplete = new BenchmarkCheckpoint(
                identity(), "corpus.jsonl", 3, 2, 2,
                BenchmarkCheckpointStatus.IN_PROGRESS, Instant.EPOCH);
        FakeIndex index = new FakeIndex(3, Map.of());

        assertThatThrownBy(() -> service(index, incomplete)
                .compare(identity(), cases(), corpus(), metadata()))
                .isInstanceOf(BenchmarkOperationException.class)
                .hasMessage("BENCHMARK_INDEX_NOT_READY");
        assertThat(index.queries).isEmpty();
    }

    @Test
    void refusesCountMismatchBeforeSearching() {
        FakeIndex index = new FakeIndex(2, Map.of());

        assertThatThrownBy(() -> service(index, completedCheckpoint())
                .compare(identity(), cases(), corpus(), metadata()))
                .isInstanceOf(BenchmarkOperationException.class)
                .hasMessage("BENCHMARK_INDEX_COUNT_MISMATCH");
        assertThat(index.queries).isEmpty();
    }

    private IsolatedVectorBenchmarkService service(FakeIndex index, BenchmarkCheckpoint checkpoint) {
        GoldSetMetricsCalculator metrics = new GoldSetMetricsCalculator();
        return new IsolatedVectorBenchmarkService(
                index,
                new FixedCheckpointStore(checkpoint),
                new IsolatedVectorEvaluator(index, metrics),
                new GoldSetBm25Evaluator(new RetrievalTokenizerService(), metrics),
                new GoldSetRunComparator(metrics));
    }

    private List<GoldSetCase> cases() {
        return List.of(new GoldSetCase(
                "q1", "Redis?", List.of("c-vector"), Map.of("c-vector", 3), "", "", "cache"));
    }

    private List<GoldSetChunk> corpus() {
        return List.of(
                new GoldSetChunk("c-vector", "d1", 0, "unrelated vector text", "", "", ""),
                new GoldSetChunk("duplicate", "d2", 0, "duplicate text", "", "", ""),
                new GoldSetChunk("c-bm25", "d3", 0, "Redis", "", "", ""));
    }

    private BenchmarkCheckpoint completedCheckpoint() {
        return new BenchmarkCheckpoint(
                identity(), "corpus.jsonl", 3, 3, 2,
                BenchmarkCheckpointStatus.COMPLETED, Instant.EPOCH);
    }

    private BenchmarkRunMetadata metadata() {
        return new BenchmarkRunMetadata("b".repeat(64), "benchmark-collection", "v1", 20, 1, 3);
    }

    private BenchmarkDatasetIdentity identity() {
        return new BenchmarkDatasetIdentity("generic-rag", "a".repeat(64), "embedding-3", 2048);
    }


    private static final class FixedCheckpointStore implements BenchmarkCheckpointStore {
        private final BenchmarkCheckpoint checkpoint;

        private FixedCheckpointStore(BenchmarkCheckpoint checkpoint) {
            this.checkpoint = checkpoint;
        }

        @Override
        public Optional<BenchmarkCheckpoint> load() {
            return Optional.ofNullable(checkpoint);
        }

        @Override
        public void save(BenchmarkCheckpoint checkpoint) {
        }
    }

    private static final class FakeIndex implements BenchmarkIndex {
        private final long count;
        private final Map<String, List<BenchmarkIndexMatch>> matches;
        private final List<String> queries = new ArrayList<>();
        private final List<Integer> requestedTopK = new ArrayList<>();

        private FakeIndex(long count, Map<String, List<BenchmarkIndexMatch>> matches) {
            this.count = count;
            this.matches = matches;
        }

        @Override
        public void upsertBatch(BenchmarkDatasetIdentity identity, List<GoldSetChunk> chunks) {
        }

        @Override
        public List<BenchmarkIndexMatch> search(BenchmarkDatasetIdentity identity, String query, int topK) {
            queries.add(query);
            requestedTopK.add(topK);
            return matches.getOrDefault(query, List.of());
        }

        @Override
        public long count(BenchmarkDatasetIdentity identity) {
            return count;
        }

        @Override
        public void deleteDataset(String datasetId) {
        }
    }
}
