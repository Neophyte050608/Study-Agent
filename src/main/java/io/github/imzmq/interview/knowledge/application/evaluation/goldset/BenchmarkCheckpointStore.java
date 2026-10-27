package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import java.util.Optional;

/** Storage boundary for resumable benchmark progress. */
public interface BenchmarkCheckpointStore {

    Optional<BenchmarkCheckpoint> load();

    void save(BenchmarkCheckpoint checkpoint);
}
