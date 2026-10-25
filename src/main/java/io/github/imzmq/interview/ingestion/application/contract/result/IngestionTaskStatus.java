package io.github.imzmq.interview.ingestion.application.contract.result;

/** Overall status of an ingestion task. */
public enum IngestionTaskStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    PARTIAL_SUCCESS,
    FAILED
}
