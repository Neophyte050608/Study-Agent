package io.github.imzmq.interview.ingestion.application.contract.result;

/** Result returned after an ingestion task has completed synchronously. */
public record IngestionTaskExecutionResult(
        String taskId,
        IngestionTaskStatus status,
        IngestionSyncSummary summary
) {
}
