package io.github.imzmq.interview.knowledge.infrastructure.evaluation;

import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkFingerprint;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkOperationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BenchmarkDatasetFileLoaderTest {

    @TempDir
    Path tempDir;

    private final BenchmarkDatasetFileLoader loader = new BenchmarkDatasetFileLoader();

    @Test
    void loadsValidatedDatasetAndHashesOriginalBytes() throws Exception {
        Path queries = tempDir.resolve("queries.jsonl");
        Path corpus = tempDir.resolve("corpus.jsonl");
        byte[] queryBytes = (validQueryJsonl() + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] corpusBytes = (validCorpusJsonl() + "\r\n").getBytes(StandardCharsets.UTF_8);
        Files.write(queries, queryBytes);
        Files.write(corpus, corpusBytes);

        ValidatedBenchmarkDataset dataset = loader.load(queries, corpus);

        assertThat(dataset.cases()).hasSize(1);
        assertThat(dataset.chunks()).hasSize(1);
        assertThat(dataset.queryFingerprint()).isEqualTo(BenchmarkFingerprint.sha256(queryBytes));
        assertThat(dataset.corpusFingerprint()).isEqualTo(BenchmarkFingerprint.sha256(corpusBytes));
    }

    @Test
    void rejectsInvalidDatasetWithContentFreeOperationCode() throws Exception {
        Path queries = tempDir.resolve("queries.jsonl");
        Path corpus = tempDir.resolve("corpus.jsonl");
        Files.writeString(queries, """
                {"query_id":"q1","question":"generic question","ground_truth":["missing"]}
                """);
        Files.writeString(corpus, validCorpusJsonl());

        assertThatThrownBy(() -> loader.load(queries, corpus))
                .isInstanceOf(BenchmarkOperationException.class)
                .hasMessage("BENCHMARK_DATASET_INVALID");
    }

    @Test
    void rejectsMissingInputWithStableOperationCode() {
        Path missing = tempDir.resolve("missing.jsonl");

        assertThatThrownBy(() -> loader.load(missing, missing))
                .isInstanceOf(BenchmarkOperationException.class)
                .hasMessage("BENCHMARK_DATASET_READ_FAILED");
    }

    private String validQueryJsonl() {
        return """
                {"query_id":"q1","question":"generic question","ground_truth":["c1"]}
                """.stripTrailing();
    }

    private String validCorpusJsonl() {
        return """
                {"chunk_id":"c1","content":"generic corpus content","metadata":{"source":"fixture"}}
                """.stripTrailing();
    }
}
