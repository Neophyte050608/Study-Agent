package io.github.imzmq.interview.interview.api;

import io.github.imzmq.interview.ingestion.application.IngestConfigService;
import io.github.imzmq.interview.ingestion.application.IngestionService;
import io.github.imzmq.interview.ingestion.application.IngestionTaskExecutionResult;
import io.github.imzmq.interview.ingestion.application.IngestionTaskService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class IngestionController {

    private final IngestionService ingestionService;
    private final IngestionTaskService ingestionTaskService;
    private final IngestConfigService ingestConfigService;

    public IngestionController(
            IngestionService ingestionService,
            IngestionTaskService ingestionTaskService,
            IngestConfigService ingestConfigService
    ) {
        this.ingestionService = ingestionService;
        this.ingestionTaskService = ingestionTaskService;
        this.ingestConfigService = ingestConfigService;
    }

    /**
     * 同步本地知识库到向量库。
     *
     * @param payload 包含 path (笔记目录) 和 ignoreDirs (忽略目录，逗号分隔)
     * @return 同步结果摘要
     */
    @PostMapping("/ingest")
    public Map<String, String> ingest(@RequestBody Map<String, String> payload) {
        // 同步本地知识库到向量库：path 指向笔记/资料目录，ignoreDirs 用逗号分隔过滤目录名。
        String path = payload.get("path");
        String ignoreDirs = payload.get("ignoreDirs");
        List<String> ignoredList = parseIgnoreDirs(ignoreDirs);
        IngestionTaskExecutionResult executionResult = ingestionTaskService.executeLocal(path, ignoredList);
        IngestionService.SyncSummary summary = executionResult.summary();
        String message = String.format(
                "同步完成：共扫描 %d 个文件，新增 %d，修改 %d，未变化 %d，删除 %d，失败 %d，空内容跳过 %d",
                summary.totalScanned,
                summary.newFiles,
                summary.modifiedFiles,
                summary.unchangedFiles,
                summary.deletedFiles,
                summary.failedFiles,
                summary.skippedEmptyFiles
        );
        return Map.of(
                "message", message,
                "taskId", executionResult.taskId(),
                "taskStatus", executionResult.status().name()
        );
    }

    @PostMapping("/ingestion/reindex/parent-child")
    public Map<String, Object> forceReindexParentChild(@RequestBody Map<String, String> payload) {
        String path = payload.get("path");
        String ignoreDirs = payload.get("ignoreDirs");
        List<String> ignoredList = parseIgnoreDirs(ignoreDirs);
        IngestionService.SyncSummary summary = ingestionService.forceReindexParentChild(path, ignoredList);
        Map<String, Object> report = ingestionService.getParentChildReport();
        String message = String.format(
                "Parent-Child 重建完成：共扫描 %d 个文件，新增 %d，重建 %d，删除 %d，失败 %d，空内容跳过 %d",
                summary.totalScanned,
                summary.newFiles,
                summary.modifiedFiles,
                summary.deletedFiles,
                summary.failedFiles,
                summary.skippedEmptyFiles
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", message);
        result.put("report", report);
        return result;
    }

    @GetMapping("/ingestion/reindex/parent-child/report")
    public ResponseEntity<Map<String, Object>> getParentChildReindexReport() {
        return ResponseEntity.ok(ingestionService.getParentChildReport());
    }

    /**
     * 上传笔记文件并同步到知识库。
     * 支持保留目录结构。
     *
     * @param files 上传的文件列表
     * @param relativePaths 文件对应的相对路径
     * @param folderName 目标文件夹名称
     * @param ignoreDirs 忽略目录
     * @return 同步结果摘要
     */
    @PostMapping("/ingest-files")
    public Map<String, String> ingestFiles(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "relativePaths", required = false) List<String> relativePaths,
            @RequestParam(value = "folderName", required = false) String folderName,
            @RequestParam(value = "ignoreDirs", required = false) String ignoreDirs) {
        // 上传笔记文件后同步：支持保留相对路径（relativePaths）以恢复目录结构。
        List<String> ignoredList = parseIgnoreDirs(ignoreDirs);
        IngestionTaskExecutionResult executionResult = ingestionTaskService.executeUpload(files, relativePaths, folderName, ignoredList);
        IngestionService.SyncSummary summary = executionResult.summary();
        String message = String.format(
                "同步完成：共扫描 %d 个文件，新增 %d，修改 %d，未变化 %d，删除 %d，失败 %d，空内容跳过 %d",
                summary.totalScanned,
                summary.newFiles,
                summary.modifiedFiles,
                summary.unchangedFiles,
                summary.deletedFiles,
                summary.failedFiles,
                summary.skippedEmptyFiles
        );
        return Map.of(
                "message", message,
                "taskId", executionResult.taskId(),
                "taskStatus", executionResult.status().name()
        );
    }

    /**
     * 解析逗号分隔的忽略目录字符串。
     */
    private List<String> parseIgnoreDirs(String ignoreDirs) {
        if (ignoreDirs == null || ignoreDirs.isBlank()) {
            return List.of();
        }
        // 输入形如 "node_modules,target,.git"。
        return Arrays.stream(ignoreDirs.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .collect(Collectors.toList());
    }

    @GetMapping("/ingestion/tasks")
    public ResponseEntity<?> listIngestionTasks(
            @RequestParam(value = "limit", required = false, defaultValue = "20") Integer limit,
            @RequestParam(value = "sourceType", required = false) String sourceType,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "includeNodeLogs", required = false, defaultValue = "false") boolean includeNodeLogs
    ) {
        int safeLimit = limit == null ? 20 : limit;
        return ResponseEntity.ok(ingestionTaskService.listTaskViews(safeLimit, sourceType, status, includeNodeLogs));
    }

    @GetMapping("/ingestion/pipelines")
    public ResponseEntity<?> listIngestionPipelines() {
        return ResponseEntity.ok(ingestionTaskService.listPipelines());
    }

    @GetMapping("/ingestion/tasks/{taskId}")
    public ResponseEntity<?> getIngestionTaskDetail(
            @PathVariable("taskId") String taskId,
            @RequestParam(value = "includeNodeLogs", required = false, defaultValue = "true") boolean includeNodeLogs
    ) {
        return ingestionTaskService.findTaskViewById(taskId, includeNodeLogs)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "任务不存在")));
    }

    /**
     * 获取知识库同步配置。
     */
    @GetMapping("/ingest/config")
    public ResponseEntity<?> getIngestConfig() {
        return ResponseEntity.ok(ingestConfigService.getConfig());
    }

    /**
     * 保存知识库同步配置。
     */
    @PostMapping("/ingest/config")
    public ResponseEntity<?> saveIngestConfig(@RequestBody Map<String, String> payload) {
        Map<String, String> saved = ingestConfigService.saveConfig(payload);
        return ResponseEntity.ok(Map.of(
                "message", "success",
                "paths", saved.get("paths"),
                "imagePath", saved.get("imagePath"),
                "ignoreDirs", saved.get("ignoreDirs")
        ));
    }
}
