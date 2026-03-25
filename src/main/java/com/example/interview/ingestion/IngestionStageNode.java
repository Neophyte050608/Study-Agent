package com.example.interview.ingestion;

public interface IngestionStageNode {

    IngestionNodeStage stage();

    IngestionStageResult execute(IngestionExecutionContext context);
}
