package com.example.interview.ingestion;

import java.util.Map;

public record IngestionStageResult(
        int inputCount,
        int outputCount,
        String message,
        Map<String, Object> details
) {
    public static IngestionStageResult of(int inputCount, int outputCount, String message) {
        return new IngestionStageResult(inputCount, outputCount, message, Map.of());
    }
}
