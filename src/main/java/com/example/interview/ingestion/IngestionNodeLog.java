package com.example.interview.ingestion;

import java.util.Map;

public record IngestionNodeLog(
        IngestionNodeStage stage,
        IngestionNodeStatus status,
        long startedAt,
        long endedAt,
        int inputCount,
        int outputCount,
        String message,
        Map<String, Object> details
) {
}
