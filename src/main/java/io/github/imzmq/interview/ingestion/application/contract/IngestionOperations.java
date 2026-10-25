package io.github.imzmq.interview.ingestion.application.contract;

import io.github.imzmq.interview.ingestion.application.contract.result.IngestionSyncSummary;

import java.util.List;
import java.util.Map;

/** Public synchronization and document-maintenance operations owned by ingestion. */
public interface IngestionOperations {

    IngestionSyncSummary forceReindexParentChild(String vaultPath, List<String> ignoredDirs);

    Map<String, Object> getStats();

    boolean deleteByFilePath(String filePath);

    boolean rechunkByFilePath(String filePath);

    Map<String, Object> getParentChildReport();
}
