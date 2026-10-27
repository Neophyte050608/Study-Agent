package io.github.imzmq.interview.knowledge.infrastructure.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkCheckpoint;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkCheckpointStore;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkOperationException;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;

/** JSON checkpoint persistence using replace-on-complete file writes. */
public final class JsonBenchmarkCheckpointStore implements BenchmarkCheckpointStore {

    private final Path target;
    private final ObjectMapper objectMapper;

    public JsonBenchmarkCheckpointStore(Path target, ObjectMapper objectMapper) {
        this.target = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public Optional<BenchmarkCheckpoint> load() {
        if (!Files.exists(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(Files.readAllBytes(target), BenchmarkCheckpoint.class));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new BenchmarkOperationException("BENCHMARK_CHECKPOINT_INVALID", exception);
        } catch (IOException exception) {
            throw new BenchmarkOperationException("BENCHMARK_CHECKPOINT_READ_FAILED", exception);
        }
    }

    @Override
    public void save(BenchmarkCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writeValue(temporary.toFile(), checkpoint);
            moveIntoPlace(temporary);
        } catch (IOException | RuntimeException exception) {
            throw new BenchmarkOperationException("BENCHMARK_CHECKPOINT_WRITE_FAILED", exception);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // The completed target remains authoritative; stale temp cleanup can be retried later.
            }
        }
    }

    private void moveIntoPlace(Path temporary) throws IOException {
        try {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
