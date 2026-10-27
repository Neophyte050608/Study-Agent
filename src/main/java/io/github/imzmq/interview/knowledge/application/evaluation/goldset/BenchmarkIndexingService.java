package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Orchestrates stable, resumable batches without depending on an embedding or index SDK. */
public final class BenchmarkIndexingService {

    private static final int MAX_BATCH_SIZE = 1_000;

    private final BenchmarkIndex index;
    private final BenchmarkCheckpointStore checkpointStore;
    private final AtomicBoolean running = new AtomicBoolean();

    public BenchmarkIndexingService(BenchmarkIndex index, BenchmarkCheckpointStore checkpointStore) {
        this.index = index;
        this.checkpointStore = checkpointStore;
    }

    public BenchmarkIndexingResult index(BenchmarkIndexCommand command) {
        if (!running.compareAndSet(false, true)) {
            throw new BenchmarkOperationException("BENCHMARK_INDEX_ALREADY_RUNNING");
        }
        try {
            validateCommand(command);
            int completedChunks = resumePosition(command, checkpointStore.load());
            List<GoldSetChunk> chunks = command.chunks();

            for (int start = completedChunks; start < chunks.size(); start += command.batchSize()) {
                int end = Math.min(start + command.batchSize(), chunks.size());
                index.upsertBatch(command.identity(), chunks.subList(start, end));
                completedChunks = end;
                checkpointStore.save(checkpoint(command, completedChunks, BenchmarkCheckpointStatus.IN_PROGRESS));
            }

            if (index.count(command.identity()) != chunks.size()) {
                throw new BenchmarkOperationException("BENCHMARK_INDEX_COUNT_MISMATCH");
            }

            BenchmarkCheckpoint completed = checkpoint(
                    command, chunks.size(), BenchmarkCheckpointStatus.COMPLETED);
            checkpointStore.save(completed);
            return new BenchmarkIndexingResult(
                    command.identity(), completed.status(), completed.completedChunks(), completed.totalChunks());
        } finally {
            running.set(false);
        }
    }

    private void validateCommand(BenchmarkIndexCommand command) {
        if (command == null || command.identity() == null) {
            throw new BenchmarkOperationException("BENCHMARK_INDEX_COMMAND_INVALID");
        }
        if (command.batchSize() < 1 || command.batchSize() > MAX_BATCH_SIZE) {
            throw new BenchmarkOperationException("BENCHMARK_BATCH_SIZE_INVALID");
        }
        if (isBlank(command.corpusSource())) {
            throw new BenchmarkOperationException("BENCHMARK_CORPUS_SOURCE_INVALID");
        }
        if (command.chunks().isEmpty()) {
            throw new BenchmarkOperationException("BENCHMARK_CORPUS_EMPTY");
        }
        for (GoldSetChunk chunk : command.chunks()) {
            if (chunk == null || isBlank(chunk.chunkId()) || isBlank(chunk.content())) {
                throw new BenchmarkOperationException("BENCHMARK_CHUNK_INVALID");
            }
        }
    }

    private int resumePosition(
            BenchmarkIndexCommand command,
            Optional<BenchmarkCheckpoint> savedCheckpoint
    ) {
        if (savedCheckpoint.isEmpty()) {
            return 0;
        }
        BenchmarkCheckpoint checkpoint = savedCheckpoint.get();
        if (!command.identity().equals(checkpoint.identity())) {
            throw new BenchmarkOperationException("BENCHMARK_CHECKPOINT_IDENTITY_MISMATCH");
        }
        if (!command.corpusSource().equals(checkpoint.corpusSource())
                || command.chunks().size() != checkpoint.totalChunks()
                || command.batchSize() != checkpoint.batchSize()) {
            throw new BenchmarkOperationException("BENCHMARK_CHECKPOINT_INPUT_MISMATCH");
        }
        if (checkpoint.completedChunks() < 0
                || checkpoint.completedChunks() > checkpoint.totalChunks()
                || checkpoint.status() == null) {
            throw new BenchmarkOperationException("BENCHMARK_CHECKPOINT_INVALID");
        }
        return checkpoint.completedChunks();
    }

    private BenchmarkCheckpoint checkpoint(
            BenchmarkIndexCommand command,
            int completedChunks,
            BenchmarkCheckpointStatus status
    ) {
        return new BenchmarkCheckpoint(
                command.identity(),
                command.corpusSource(),
                command.chunks().size(),
                completedChunks,
                command.batchSize(),
                status,
                Instant.now());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
