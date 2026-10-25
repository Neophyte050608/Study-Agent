package io.github.imzmq.interview.ingestion.application.contract;

import io.github.imzmq.interview.ingestion.application.contract.result.IngestionTaskExecutionResult;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Public task execution and operational-query surface owned by ingestion. */
public interface IngestionTaskOperations {

    IngestionTaskExecutionResult executeLocal(String vaultPath, List<String> ignoredDirs);

    IngestionTaskExecutionResult executeUpload(
            List<IngestionUpload> files,
            List<String> relativePaths,
            String folderName,
            List<String> ignoredDirs
    );

    List<Map<String, Object>> listTaskViews(
            int limit,
            String sourceType,
            String status,
            boolean includeNodeLogs
    );

    Optional<Map<String, Object>> findTaskViewById(String taskId, boolean includeNodeLogs);

    boolean hasRunningTasks();

    List<Map<String, Object>> listPipelineViews();

    Map<String, Object> buildOverview(
            int recentLimit,
            Integer windowMinutes,
            String sourceType,
            boolean groupBySourceType
    );
}
