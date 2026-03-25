package com.example.interview.ingestion;

import java.util.List;
import java.util.Map;

public record IngestionTaskSnapshot(
        String taskId,
        String pipelineName,
        String sourceType,
        IngestionTaskStatus status,
        long startedAt,
        long endedAt,
        Map<String, Object> summary,
        List<IngestionNodeLog> nodeLogs,
        String errorMessage
) {
}
