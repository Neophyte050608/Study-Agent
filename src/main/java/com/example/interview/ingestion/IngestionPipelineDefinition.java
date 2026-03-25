package com.example.interview.ingestion;

import java.util.List;

public record IngestionPipelineDefinition(
        String name,
        String sourceType,
        boolean enabled,
        List<IngestionNodeStage> stages
) {
}
