package io.github.imzmq.interview.knowledge.infrastructure.evaluation;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkCheckpoint;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkCheckpointStatus;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkDatasetIdentity;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkOperationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonBenchmarkCheckpointStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void atomicallyRoundTripsCheckpointWithoutSensitiveContent() throws Exception {
        Path path = tempDir.resolve("nested/checkpoint.json");
        JsonBenchmarkCheckpointStore store = new JsonBenchmarkCheckpointStore(
                path, JsonMapper.builder().findAndAddModules().build());
        BenchmarkCheckpoint checkpoint = checkpoint();

        store.save(checkpoint);

        assertThat(store.load()).contains(checkpoint);
        assertThat(Files.readString(path)).doesNotContain("chunk body", "api-key");
        assertThat(tempSiblingFiles(path)).isEmpty();
    }

    @Test
    void returnsEmptyWhenCheckpointDoesNotExist() {
        JsonBenchmarkCheckpointStore store = new JsonBenchmarkCheckpointStore(
                tempDir.resolve("checkpoint.json"), JsonMapper.builder().findAndAddModules().build());

        assertThat(store.load()).isEmpty();
    }

    @Test
    void rejectsMalformedCheckpointWithStableCode() throws Exception {
        Path path = tempDir.resolve("checkpoint.json");
        Files.writeString(path, "{not-json");
        JsonBenchmarkCheckpointStore store = new JsonBenchmarkCheckpointStore(
                path, JsonMapper.builder().findAndAddModules().build());

        assertThatThrownBy(store::load)
                .isInstanceOf(BenchmarkOperationException.class)
                .hasMessage("BENCHMARK_CHECKPOINT_INVALID");
    }

    private BenchmarkCheckpoint checkpoint() {
        return new BenchmarkCheckpoint(
                new BenchmarkDatasetIdentity("generic-rag", "a".repeat(64), "embedding-3", 2048),
                "corpus.jsonl",
                5,
                2,
                2,
                BenchmarkCheckpointStatus.IN_PROGRESS,
                Instant.parse("2026-10-27T00:00:00Z"));
    }

    private List<Path> tempSiblingFiles(Path target) throws Exception {
        Path parent = target.getParent();
        if (!Files.exists(parent)) {
            return List.of();
        }
        try (var paths = Files.list(parent)) {
            return paths.filter(path -> path.getFileName().toString().startsWith(target.getFileName() + ".tmp"))
                    .toList();
        }
    }
}
