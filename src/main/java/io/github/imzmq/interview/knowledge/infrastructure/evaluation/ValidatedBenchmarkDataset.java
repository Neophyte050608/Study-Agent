package io.github.imzmq.interview.knowledge.infrastructure.evaluation;

import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetCase;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetChunk;

import java.util.List;

/** Immutable, validated benchmark inputs and fingerprints of their original bytes. */
public record ValidatedBenchmarkDataset(
        List<GoldSetCase> cases,
        List<GoldSetChunk> chunks,
        String queryFingerprint,
        String corpusFingerprint
) {
    public ValidatedBenchmarkDataset {
        cases = List.copyOf(cases);
        chunks = List.copyOf(chunks);
    }
}
