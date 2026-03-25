package com.example.interview.ingestion;

import com.example.interview.service.IngestionService;

public record IngestionTaskExecutionResult(
        String taskId,
        IngestionTaskStatus status,
        IngestionService.SyncSummary summary
) {
}
