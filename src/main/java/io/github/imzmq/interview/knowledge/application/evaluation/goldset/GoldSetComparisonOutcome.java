package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

/** Per-query winner when a candidate retrieval run is compared with a baseline. */
public enum GoldSetComparisonOutcome {
    CANDIDATE_WIN,
    BASELINE_WIN,
    TIE
}
