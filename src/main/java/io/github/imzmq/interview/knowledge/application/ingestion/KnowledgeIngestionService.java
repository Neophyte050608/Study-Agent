package io.github.imzmq.interview.knowledge.application.ingestion;

import io.github.imzmq.interview.knowledge.internal.ingestion.application.IngestConfigService;
import io.github.imzmq.interview.knowledge.internal.ingestion.application.IngestionService;
import io.github.imzmq.interview.knowledge.internal.ingestion.application.IngestionTaskExecutionResult;
import io.github.imzmq.interview.knowledge.internal.ingestion.application.IngestionTaskService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Knowledge ingestion application facade.
 *
 * <p>Exposes the small set of ingestion operations needed by external modules
 * without leaking knowledge.internal implementation packages.</p>
 */
@Service
public class KnowledgeIngestionService {

    private final IngestionService ingestionService;
    private final IngestionTaskService ingestionTaskService;
    private final IngestConfigService ingestConfigService;

    public KnowledgeIngestionService(
            IngestionService ingestionService,
            IngestionTaskService ingestionTaskService,
            IngestConfigService ingestConfigService
    ) {
        this.ingestionService = ingestionService;
        this.ingestionTaskService = ingestionTaskService;
        this.ingestConfigService = ingestConfigService;
    }

    public IngestionExecutionResult executeLocal(String path, List<String> ignoredDirs) {
        return toResult(ingestionTaskService.executeLocal(path, ignoredDirs));
    }

    public IngestionExecutionResult executeUpload(
            List<MultipartFile> files,
            List<String> relativePaths,
            String folderName,
            List<String> ignoredDirs
    ) {
        return toResult(ingestionTaskService.executeUpload(files, relativePaths, folderName, ignoredDirs));
    }

    public IngestionSummary forceReindexParentChild(String path, List<String> ignoredDirs) {
        return toSummary(ingestionService.forceReindexParentChild(path, ignoredDirs));
    }

    public Map<String, Object> getParentChildReport() {
        return ingestionService.getParentChildReport();
    }

    public List<Map<String, Object>> listTaskViews(int limit, String sourceType, String status, boolean includeNodeLogs) {
        return ingestionTaskService.listTaskViews(limit, sourceType, status, includeNodeLogs);
    }

    public List<?> listPipelines() {
        return ingestionTaskService.listPipelines();
    }

    public Optional<Map<String, Object>> findTaskViewById(String taskId, boolean includeNodeLogs) {
        return ingestionTaskService.findTaskViewById(taskId, includeNodeLogs);
    }

    public Map<String, String> getConfig() {
        return ingestConfigService.getConfig();
    }

    public Map<String, String> saveConfig(Map<String, String> payload) {
        return ingestConfigService.saveConfig(payload);
    }

    public Map<String, Object> getStats() {
        return ingestionService.getStats();
    }

    public Map<String, Object> buildOverview(
            int recentLimit,
            Integer windowMinutes,
            String sourceType,
            boolean groupBySourceType
    ) {
        return ingestionTaskService.buildOverview(recentLimit, windowMinutes, sourceType, groupBySourceType);
    }

    private IngestionExecutionResult toResult(IngestionTaskExecutionResult result) {
        return new IngestionExecutionResult(
                result.taskId(),
                result.status().name(),
                toSummary(result.summary())
        );
    }

    private IngestionSummary toSummary(IngestionService.SyncSummary summary) {
        return new IngestionSummary(
                summary.totalScanned,
                summary.newFiles,
                summary.modifiedFiles,
                summary.unchangedFiles,
                summary.deletedFiles,
                summary.failedFiles,
                summary.skippedEmptyFiles
        );
    }

    public record IngestionExecutionResult(
            String taskId,
            String status,
            IngestionSummary summary
    ) {
    }

    public record IngestionSummary(
            int totalScanned,
            int newFiles,
            int modifiedFiles,
            int unchangedFiles,
            int deletedFiles,
            int failedFiles,
            int skippedEmptyFiles
    ) {
    }
}
