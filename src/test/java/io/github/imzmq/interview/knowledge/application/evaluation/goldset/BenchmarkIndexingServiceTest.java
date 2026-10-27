package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkCheckpointStatus.IN_PROGRESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BenchmarkIndexingServiceTest {

    @Test
    void indexesStableBatchesAndCompletesAfterCountMatches() {
        FakeIndex index = new FakeIndex(5);
        FakeCheckpointStore checkpoints = new FakeCheckpointStore();
        BenchmarkIndexingService service = new BenchmarkIndexingService(index, checkpoints);

        BenchmarkIndexingResult result = service.index(command(5, 2));

        assertThat(index.batchIds()).containsExactly(
                List.of("c0", "c1"), List.of("c2", "c3"), List.of("c4"));
        assertThat(checkpoints.saved()).extracting(BenchmarkCheckpoint::completedChunks)
                .containsExactly(2, 4, 5, 5);
        assertThat(result.status()).isEqualTo(BenchmarkCheckpointStatus.COMPLETED);
        assertThat(result.completedChunks()).isEqualTo(5);
    }

    @Test
    void resumesAtCheckpointWithoutReembeddingEarlierChunks() {
        FakeCheckpointStore checkpoints = new FakeCheckpointStore(checkpoint(2, IN_PROGRESS));
        FakeIndex index = new FakeIndex(5);
        BenchmarkIndexingService service = new BenchmarkIndexingService(index, checkpoints);

        service.index(command(5, 2));

        assertThat(index.batchIds()).containsExactly(List.of("c2", "c3"), List.of("c4"));
    }

    @Test
    void rejectsCheckpointFromAnotherIdentityBeforeIndexing() {
        BenchmarkCheckpoint incompatible = new BenchmarkCheckpoint(
                new BenchmarkDatasetIdentity("other", "b".repeat(64), "embedding-3", 2048),
                "corpus.jsonl", 5, 2, 2, IN_PROGRESS, Instant.EPOCH);
        FakeCheckpointStore checkpoints = new FakeCheckpointStore(incompatible);
        FakeIndex index = new FakeIndex(5);

        assertThatThrownBy(() -> new BenchmarkIndexingService(index, checkpoints).index(command(5, 2)))
                .isInstanceOf(BenchmarkOperationException.class)
                .hasMessage("BENCHMARK_CHECKPOINT_IDENTITY_MISMATCH");
        assertThat(index.batchIds()).isEmpty();
    }

    @Test
    void rejectsInvalidBatchSizeBeforeIndexing() {
        FakeIndex index = new FakeIndex(1);

        assertThatThrownBy(() -> new BenchmarkIndexingService(index, new FakeCheckpointStore())
                .index(command(1, 0)))
                .isInstanceOf(BenchmarkOperationException.class)
                .hasMessage("BENCHMARK_BATCH_SIZE_INVALID");
        assertThat(index.batchIds()).isEmpty();
    }

    @Test
    void refusesCompletionWhenIndexCountDoesNotMatchCorpus() {
        FakeCheckpointStore checkpoints = new FakeCheckpointStore();
        FakeIndex index = new FakeIndex(4);

        assertThatThrownBy(() -> new BenchmarkIndexingService(index, checkpoints).index(command(5, 2)))
                .isInstanceOf(BenchmarkOperationException.class)
                .hasMessage("BENCHMARK_INDEX_COUNT_MISMATCH");
        assertThat(checkpoints.saved()).noneMatch(
                item -> item.status() == BenchmarkCheckpointStatus.COMPLETED);
    }

    @Test
    void rejectsSecondConcurrentIndexingCall() throws Exception {
        BlockingIndex index = new BlockingIndex();
        BenchmarkIndexingService service = new BenchmarkIndexingService(index, new FakeCheckpointStore());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<BenchmarkIndexingResult> first = executor.submit(() -> service.index(command(1, 1)));
            assertThat(index.entered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> service.index(command(1, 1)))
                    .isInstanceOf(BenchmarkOperationException.class)
                    .hasMessage("BENCHMARK_INDEX_ALREADY_RUNNING");

            index.release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).status())
                    .isEqualTo(BenchmarkCheckpointStatus.COMPLETED);
        } finally {
            index.release.countDown();
            executor.shutdownNow();
        }
    }

    private BenchmarkIndexCommand command(int chunkCount, int batchSize) {
        List<GoldSetChunk> chunks = new ArrayList<>();
        for (int index = 0; index < chunkCount; index++) {
            chunks.add(new GoldSetChunk("c" + index, "doc", index, "content " + index,
                    "", "", ""));
        }
        return new BenchmarkIndexCommand(identity(), "corpus.jsonl", chunks, batchSize);
    }

    private BenchmarkCheckpoint checkpoint(int completed, BenchmarkCheckpointStatus status) {
        return new BenchmarkCheckpoint(identity(), "corpus.jsonl", 5, completed, 2, status, Instant.EPOCH);
    }

    private BenchmarkDatasetIdentity identity() {
        return new BenchmarkDatasetIdentity("generic-rag", "a".repeat(64), "embedding-3", 2048);
    }

    private static class FakeIndex implements BenchmarkIndex {
        private final long count;
        private final List<List<String>> batches = new ArrayList<>();

        private FakeIndex(long count) {
            this.count = count;
        }

        @Override
        public void upsertBatch(BenchmarkDatasetIdentity identity, List<GoldSetChunk> chunks) {
            batches.add(chunks.stream().map(GoldSetChunk::chunkId).toList());
        }

        @Override
        public List<BenchmarkIndexMatch> search(
                BenchmarkDatasetIdentity identity, String query, int topK) {
            return List.of();
        }

        @Override
        public long count(BenchmarkDatasetIdentity identity) {
            return count;
        }

        @Override
        public void deleteDataset(String datasetId) {
        }

        List<List<String>> batchIds() {
            return List.copyOf(batches);
        }
    }

    private static final class BlockingIndex extends FakeIndex {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private BlockingIndex() {
            super(1);
        }

        @Override
        public void upsertBatch(BenchmarkDatasetIdentity identity, List<GoldSetChunk> chunks) {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test interrupted", exception);
            }
            super.upsertBatch(identity, chunks);
        }
    }

    private static final class FakeCheckpointStore implements BenchmarkCheckpointStore {
        private BenchmarkCheckpoint current;
        private final List<BenchmarkCheckpoint> saved = new ArrayList<>();

        private FakeCheckpointStore() {
        }

        private FakeCheckpointStore(BenchmarkCheckpoint current) {
            this.current = current;
        }

        @Override
        public Optional<BenchmarkCheckpoint> load() {
            return Optional.ofNullable(current);
        }

        @Override
        public void save(BenchmarkCheckpoint checkpoint) {
            current = checkpoint;
            saved.add(checkpoint);
        }

        List<BenchmarkCheckpoint> saved() {
            return List.copyOf(saved);
        }
    }
}
