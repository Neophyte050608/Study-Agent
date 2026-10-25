package io.github.imzmq.interview.ingestion.application.contract.result;

/** Immutable summary of one ingestion synchronization run. */
public final class IngestionSyncSummary {

    public final int totalScanned;
    public final int newFiles;
    public final int modifiedFiles;
    public final int unchangedFiles;
    public final int deletedFiles;
    public final int failedFiles;
    public final int skippedEmptyFiles;

    public IngestionSyncSummary(
            int totalScanned,
            int newFiles,
            int modifiedFiles,
            int unchangedFiles,
            int deletedFiles,
            int failedFiles,
            int skippedEmptyFiles
    ) {
        this.totalScanned = totalScanned;
        this.newFiles = newFiles;
        this.modifiedFiles = modifiedFiles;
        this.unchangedFiles = unchangedFiles;
        this.deletedFiles = deletedFiles;
        this.failedFiles = failedFiles;
        this.skippedEmptyFiles = skippedEmptyFiles;
    }

    public String toLogMessage() {
        return String.format(
                "total=%d, new=%d, modified=%d, unchanged=%d, deleted=%d, failed=%d, skippedEmpty=%d",
                totalScanned,
                newFiles,
                modifiedFiles,
                unchangedFiles,
                deletedFiles,
                failedFiles,
                skippedEmptyFiles
        );
    }
}
