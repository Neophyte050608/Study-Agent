package io.github.imzmq.interview.learning.application.contract.result;

/** Reference to retrieved content, never an LLM-generated citation. */
public record LearningPlanEvidence(String documentId, String title, String excerpt) { }
