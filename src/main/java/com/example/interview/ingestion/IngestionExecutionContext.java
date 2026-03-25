package com.example.interview.ingestion;

import com.example.interview.service.IngestionService;

import java.util.function.Supplier;

public class IngestionExecutionContext {

    private final Supplier<IngestionService.SyncSummary> syncSupplier;
    private final Supplier<Integer> sourceCountSupplier;
    private final Supplier<Integer> parsedCountSupplier;
    private final Supplier<Integer> enhancedCountSupplier;
    private final Supplier<Integer> chunkCountSupplier;
    private int currentCount;
    private IngestionService.SyncSummary summary;
    private Integer parsedCount;
    private Integer enhancedCount;
    private Integer chunkCount;

    public IngestionExecutionContext(
            Supplier<IngestionService.SyncSummary> syncSupplier,
            Supplier<Integer> sourceCountSupplier,
            Supplier<Integer> parsedCountSupplier,
            Supplier<Integer> enhancedCountSupplier,
            Supplier<Integer> chunkCountSupplier
    ) {
        this.syncSupplier = syncSupplier;
        this.sourceCountSupplier = sourceCountSupplier;
        this.parsedCountSupplier = parsedCountSupplier;
        this.enhancedCountSupplier = enhancedCountSupplier;
        this.chunkCountSupplier = chunkCountSupplier;
    }

    public Supplier<IngestionService.SyncSummary> getSyncSupplier() {
        return syncSupplier;
    }

    public Supplier<Integer> getSourceCountSupplier() {
        return sourceCountSupplier;
    }

    public int resolveParsedCount() {
        if (parsedCount == null) {
            parsedCount = parsedCountSupplier.get();
        }
        return parsedCount;
    }

    public int resolveChunkCount() {
        if (chunkCount == null) {
            chunkCount = chunkCountSupplier.get();
        }
        return chunkCount;
    }

    public int resolveEnhancedCount() {
        if (enhancedCount == null) {
            enhancedCount = enhancedCountSupplier.get();
        }
        return enhancedCount;
    }

    public int getCurrentCount() {
        return currentCount;
    }

    public void setCurrentCount(int currentCount) {
        this.currentCount = currentCount;
    }

    public IngestionService.SyncSummary getSummary() {
        return summary;
    }

    public void setSummary(IngestionService.SyncSummary summary) {
        this.summary = summary;
    }
}
