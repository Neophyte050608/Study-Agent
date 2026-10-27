package io.github.imzmq.interview.knowledge.infrastructure.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkFingerprint;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkOperationException;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetCase;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetChunk;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetDataLoader;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetDatasetValidationException;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetDatasetValidator;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Reads, fingerprints, parses, and validates generic benchmark JSONL files. */
public final class BenchmarkDatasetFileLoader {

    private final GoldSetDataLoader dataLoader;
    private final GoldSetDatasetValidator validator;

    public BenchmarkDatasetFileLoader() {
        this(new ObjectMapper());
    }

    public BenchmarkDatasetFileLoader(ObjectMapper objectMapper) {
        this(new GoldSetDataLoader(objectMapper), new GoldSetDatasetValidator());
    }

    BenchmarkDatasetFileLoader(GoldSetDataLoader dataLoader, GoldSetDatasetValidator validator) {
        this.dataLoader = dataLoader;
        this.validator = validator;
    }

    public ValidatedBenchmarkDataset load(Path queryFile, Path corpusFile) {
        byte[] queryBytes = read(queryFile);
        byte[] corpusBytes = read(corpusFile);
        try {
            List<GoldSetCase> cases = dataLoader.loadGoldCases(reader(queryBytes));
            List<GoldSetChunk> chunks = dataLoader.loadChunks(reader(corpusBytes));
            validator.validate(cases, chunks);
            return new ValidatedBenchmarkDataset(
                    cases,
                    chunks,
                    BenchmarkFingerprint.sha256(queryBytes),
                    BenchmarkFingerprint.sha256(corpusBytes));
        } catch (IOException | GoldSetDatasetValidationException exception) {
            throw new BenchmarkOperationException("BENCHMARK_DATASET_INVALID", exception);
        }
    }

    private byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException | RuntimeException exception) {
            throw new BenchmarkOperationException("BENCHMARK_DATASET_READ_FAILED", exception);
        }
    }

    private StringReader reader(byte[] bytes) {
        return new StringReader(new String(bytes, StandardCharsets.UTF_8));
    }
}
