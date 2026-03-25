package com.example.interview.ingestion;

import com.example.interview.rag.DocumentSplitter;
import com.example.interview.rag.NoteLoader;
import com.example.interview.rag.ObsidianKnowledgeExtractor;
import com.example.interview.service.IngestionService;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
public class IngestionTaskService {

    private static final int MAX_HISTORY = 100;
    private static final String SOURCE_TYPE_LOCAL = "LOCAL_VAULT";
    private static final String SOURCE_TYPE_UPLOAD = "BROWSER_UPLOAD";

    private final IngestionService ingestionService;
    private final IngestionPipelineRegistry pipelineRegistry;
    private final IngestionStageNodeRegistry stageNodeRegistry;
    private final NoteLoader noteLoader;
    private final ObsidianKnowledgeExtractor knowledgeExtractor;
    private final DocumentSplitter documentSplitter;
    private final ConcurrentLinkedDeque<String> historyOrder = new ConcurrentLinkedDeque<>();
    private final ConcurrentMap<String, IngestionTaskSnapshot> snapshots = new ConcurrentHashMap<>();

    public IngestionTaskService(
            IngestionService ingestionService,
            IngestionPipelineRegistry pipelineRegistry,
            IngestionStageNodeRegistry stageNodeRegistry,
            NoteLoader noteLoader,
            ObsidianKnowledgeExtractor knowledgeExtractor,
            DocumentSplitter documentSplitter
    ) {
        this.ingestionService = ingestionService;
        this.pipelineRegistry = pipelineRegistry;
        this.stageNodeRegistry = stageNodeRegistry;
        this.noteLoader = noteLoader;
        this.knowledgeExtractor = knowledgeExtractor;
        this.documentSplitter = documentSplitter;
    }

    public IngestionTaskExecutionResult executeLocal(String vaultPath, List<String> ignoredDirs) {
        IngestionPipelineDefinition pipeline = resolveEnabledPipeline(SOURCE_TYPE_LOCAL);
        String taskId = UUID.randomUUID().toString();
        long startedAt = System.currentTimeMillis();
        List<IngestionNodeLog> nodeLogs = new ArrayList<>();
        try {
            IngestionService.SyncSummary summary = runPipeline(
                    pipeline,
                    nodeLogs,
                    () -> ingestionService.sync(vaultPath, ignoredDirs),
                    () -> countLocalSource(vaultPath, ignoredDirs),
                    () -> countParsedLocal(vaultPath, ignoredDirs),
                    () -> countEnhancedLocal(vaultPath, ignoredDirs),
                    () -> countChunkLocal(vaultPath, ignoredDirs)
            );
            long endedAt = System.currentTimeMillis();
            IngestionTaskStatus status = resolveTaskStatus(summary);
            IngestionTaskSnapshot snapshot = new IngestionTaskSnapshot(
                    taskId,
                    pipeline.name(),
                    SOURCE_TYPE_LOCAL,
                    status,
                    startedAt,
                    endedAt,
                    buildSummaryMap(summary),
                    List.copyOf(nodeLogs),
                    null
            );
            saveSnapshot(snapshot);
            return new IngestionTaskExecutionResult(taskId, status, summary);
        } catch (RuntimeException e) {
            long endedAt = System.currentTimeMillis();
            appendFallbackFailureIfAbsent(nodeLogs, startedAt, endedAt, e.getMessage());
            IngestionTaskSnapshot snapshot = new IngestionTaskSnapshot(
                    taskId,
                    pipeline.name(),
                    SOURCE_TYPE_LOCAL,
                    IngestionTaskStatus.FAILED,
                    startedAt,
                    endedAt,
                    buildFailureSummaryMap(),
                    List.copyOf(nodeLogs),
                    e.getMessage()
            );
            saveSnapshot(snapshot);
            throw e;
        }
    }

    public IngestionTaskExecutionResult executeUpload(List<MultipartFile> files, List<String> relativePaths, String folderName, List<String> ignoredDirs) {
        IngestionPipelineDefinition pipeline = resolveEnabledPipeline(SOURCE_TYPE_UPLOAD);
        String taskId = UUID.randomUUID().toString();
        long startedAt = System.currentTimeMillis();
        List<IngestionNodeLog> nodeLogs = new ArrayList<>();
        try {
            IngestionService.SyncSummary summary = runPipeline(
                    pipeline,
                    nodeLogs,
                    () -> ingestionService.syncUploadedNotes(files, relativePaths, folderName, ignoredDirs),
                    () -> countUploadSource(files, relativePaths, ignoredDirs),
                    () -> countParsedUpload(files, relativePaths, ignoredDirs),
                    () -> countEnhancedUpload(files, relativePaths, ignoredDirs),
                    () -> countChunkUpload(files, relativePaths, ignoredDirs)
            );
            long endedAt = System.currentTimeMillis();
            IngestionTaskStatus status = resolveTaskStatus(summary);
            IngestionTaskSnapshot snapshot = new IngestionTaskSnapshot(
                    taskId,
                    pipeline.name(),
                    SOURCE_TYPE_UPLOAD,
                    status,
                    startedAt,
                    endedAt,
                    buildSummaryMap(summary),
                    List.copyOf(nodeLogs),
                    null
            );
            saveSnapshot(snapshot);
            return new IngestionTaskExecutionResult(taskId, status, summary);
        } catch (RuntimeException e) {
            long endedAt = System.currentTimeMillis();
            appendFallbackFailureIfAbsent(nodeLogs, startedAt, endedAt, e.getMessage());
            IngestionTaskSnapshot snapshot = new IngestionTaskSnapshot(
                    taskId,
                    pipeline.name(),
                    SOURCE_TYPE_UPLOAD,
                    IngestionTaskStatus.FAILED,
                    startedAt,
                    endedAt,
                    buildFailureSummaryMap(),
                    List.copyOf(nodeLogs),
                    e.getMessage()
            );
            saveSnapshot(snapshot);
            throw e;
        }
    }

    public List<IngestionTaskSnapshot> listTasks(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_HISTORY));
        List<IngestionTaskSnapshot> result = new ArrayList<>();
        for (String taskId : historyOrder) {
            IngestionTaskSnapshot snapshot = snapshots.get(taskId);
            if (snapshot != null) {
                result.add(snapshot);
            }
            if (result.size() >= safeLimit) {
                break;
            }
        }
        return result;
    }

    public List<IngestionTaskSnapshot> listTasks(int limit, String sourceType, String status) {
        List<IngestionTaskSnapshot> snapshots = listTasks(limit);
        String normalizedSourceType = normalizeOptional(sourceType);
        String normalizedStatus = normalizeOptional(status);
        return snapshots.stream()
                .filter(item -> normalizedSourceType == null || item.sourceType().equalsIgnoreCase(normalizedSourceType))
                .filter(item -> normalizedStatus == null || item.status().name().equalsIgnoreCase(normalizedStatus))
                .toList();
    }

    public List<Map<String, Object>> listTaskViews(int limit, String sourceType, String status, boolean includeNodeLogs) {
        List<IngestionTaskSnapshot> snapshots = listTasks(limit, sourceType, status);
        List<Map<String, Object>> result = new ArrayList<>();
        for (IngestionTaskSnapshot snapshot : snapshots) {
            result.add(toTaskView(snapshot, includeNodeLogs));
        }
        return result;
    }

    public Optional<IngestionTaskSnapshot> findTaskById(String taskId) {
        return Optional.ofNullable(snapshots.get(taskId));
    }

    public Optional<Map<String, Object>> findTaskViewById(String taskId, boolean includeNodeLogs) {
        return findTaskById(taskId).map(item -> toTaskView(item, includeNodeLogs));
    }

    public List<IngestionPipelineDefinition> listPipelines() {
        return pipelineRegistry.listPipelines();
    }

    public Map<String, Object> buildOverview(int recentLimit) {
        return buildOverview(recentLimit, null, null, true);
    }

    public Map<String, Object> buildOverview(
            int recentLimit,
            Integer windowMinutes,
            String sourceType,
            boolean groupBySourceType
    ) {
        List<IngestionTaskSnapshot> tasks = listTasks(recentLimit, sourceType, null);
        if (windowMinutes != null && windowMinutes > 0) {
            long windowStartAt = System.currentTimeMillis() - windowMinutes.longValue() * 60_000L;
            tasks = tasks.stream().filter(task -> task.startedAt() >= windowStartAt).toList();
        }
        int success = 0;
        int partialSuccess = 0;
        int failed = 0;
        int running = 0;
        for (IngestionTaskSnapshot task : tasks) {
            switch (task.status()) {
                case SUCCESS -> success++;
                case PARTIAL_SUCCESS -> partialSuccess++;
                case FAILED -> failed++;
                case RUNNING, PENDING -> running++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recentTaskCount", tasks.size());
        result.put("recentSuccessCount", success);
        result.put("recentPartialSuccessCount", partialSuccess);
        result.put("recentFailedCount", failed);
        result.put("recentRunningCount", running);
        result.put("appliedWindowMinutes", windowMinutes);
        result.put("appliedSourceType", normalizeOptional(sourceType));
        List<Map<String, Object>> recentTasks = new ArrayList<>();
        for (IngestionTaskSnapshot task : tasks) {
            recentTasks.add(toTaskView(task, false));
        }
        result.put("recentTasks", recentTasks);
        result.put("hotStages", buildHotStages(tasks, 5));
        if (groupBySourceType) {
            result.put("hotStagesBySourceType", buildHotStagesBySourceType(tasks, 3));
        }
        return result;
    }

    private Map<String, Object> toTaskView(IngestionTaskSnapshot snapshot, boolean includeNodeLogs) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", snapshot.taskId());
        result.put("pipelineName", snapshot.pipelineName());
        result.put("sourceType", snapshot.sourceType());
        result.put("status", snapshot.status().name());
        result.put("startedAt", snapshot.startedAt());
        result.put("endedAt", snapshot.endedAt());
        result.put("durationMs", Math.max(0, snapshot.endedAt() - snapshot.startedAt()));
        result.put("summary", snapshot.summary());
        result.put("errorMessage", snapshot.errorMessage());
        result.put("stageOverview", buildStageOverview(snapshot.nodeLogs()));
        result.put("hotStages", buildHotStages(List.of(snapshot), 3));
        if (includeNodeLogs) {
            result.put("nodeLogs", snapshot.nodeLogs());
        }
        return result;
    }

    private List<Map<String, Object>> buildStageOverview(List<IngestionNodeLog> nodeLogs) {
        List<Map<String, Object>> result = new ArrayList<>();
        long totalDuration = 0L;
        for (IngestionNodeLog nodeLog : nodeLogs) {
            totalDuration += Math.max(0, nodeLog.endedAt() - nodeLog.startedAt());
        }
        for (IngestionNodeLog nodeLog : nodeLogs) {
            long duration = Math.max(0, nodeLog.endedAt() - nodeLog.startedAt());
            Map<String, Object> stage = new LinkedHashMap<>();
            stage.put("stage", nodeLog.stage().name());
            stage.put("status", nodeLog.status().name());
            stage.put("durationMs", duration);
            stage.put("durationRatio", calculateRatio(duration, totalDuration));
            stage.put("inputCount", nodeLog.inputCount());
            stage.put("outputCount", nodeLog.outputCount());
            stage.put("message", nodeLog.message());
            stage.put("details", nodeLog.details());
            result.add(stage);
        }
        return result;
    }

    private List<Map<String, Object>> buildHotStages(List<IngestionTaskSnapshot> tasks, int topN) {
        Map<String, StageAggregate> stageAggregates = new LinkedHashMap<>();
        for (IngestionTaskSnapshot task : tasks) {
            for (IngestionNodeLog nodeLog : task.nodeLogs()) {
                String stageName = nodeLog.stage().name();
                StageAggregate aggregate = stageAggregates.computeIfAbsent(stageName, key -> new StageAggregate());
                long duration = Math.max(0, nodeLog.endedAt() - nodeLog.startedAt());
                aggregate.totalDurationMs += duration;
                aggregate.invocations += 1;
                if (nodeLog.status() == IngestionNodeStatus.FAILED) {
                    aggregate.failedInvocations += 1;
                }
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        stageAggregates.entrySet().stream()
                .sorted(Comparator.comparingLong(entry -> -entry.getValue().totalDurationMs))
                .limit(Math.max(1, topN))
                .forEach(entry -> {
                    String stageName = entry.getKey();
                    StageAggregate aggregate = entry.getValue();
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("stage", stageName);
                    item.put("totalDurationMs", aggregate.totalDurationMs);
                    item.put("invocations", aggregate.invocations);
                    item.put("avgDurationMs", aggregate.invocations == 0 ? 0 : aggregate.totalDurationMs / aggregate.invocations);
                    item.put("failedInvocations", aggregate.failedInvocations);
                    item.put("failedRatio", calculateRatio(aggregate.failedInvocations, aggregate.invocations));
                    result.add(item);
                });
        return result;
    }

    private Map<String, List<Map<String, Object>>> buildHotStagesBySourceType(List<IngestionTaskSnapshot> tasks, int topN) {
        Map<String, List<IngestionTaskSnapshot>> grouped = tasks.stream()
                .collect(Collectors.groupingBy(IngestionTaskSnapshot::sourceType, LinkedHashMap::new, Collectors.toList()));
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<IngestionTaskSnapshot>> entry : grouped.entrySet()) {
            result.put(entry.getKey(), buildHotStages(entry.getValue(), topN));
        }
        return result;
    }

    private double calculateRatio(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0d;
        }
        return (double) numerator / denominator;
    }

    private void saveSnapshot(IngestionTaskSnapshot snapshot) {
        snapshots.put(snapshot.taskId(), snapshot);
        historyOrder.remove(snapshot.taskId());
        historyOrder.addFirst(snapshot.taskId());
        while (historyOrder.size() > MAX_HISTORY) {
            String removedTaskId = historyOrder.pollLast();
            if (removedTaskId != null) {
                snapshots.remove(removedTaskId);
            }
        }
    }

    private IngestionTaskStatus resolveTaskStatus(IngestionService.SyncSummary summary) {
        if (summary.failedFiles > 0) {
            return IngestionTaskStatus.PARTIAL_SUCCESS;
        }
        return IngestionTaskStatus.SUCCESS;
    }

    private Map<String, Object> buildSummaryMap(IngestionService.SyncSummary summary) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalScanned", summary.totalScanned);
        result.put("newFiles", summary.newFiles);
        result.put("modifiedFiles", summary.modifiedFiles);
        result.put("unchangedFiles", summary.unchangedFiles);
        result.put("deletedFiles", summary.deletedFiles);
        result.put("failedFiles", summary.failedFiles);
        result.put("skippedEmptyFiles", summary.skippedEmptyFiles);
        return result;
    }

    private Map<String, Object> buildFailureSummaryMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalScanned", 0);
        result.put("newFiles", 0);
        result.put("modifiedFiles", 0);
        result.put("unchangedFiles", 0);
        result.put("deletedFiles", 0);
        result.put("failedFiles", 1);
        result.put("skippedEmptyFiles", 0);
        return result;
    }

    private IngestionPipelineDefinition resolveEnabledPipeline(String sourceType) {
        IngestionPipelineDefinition pipeline = pipelineRegistry.resolveRequiredBySource(sourceType);
        if (!pipeline.enabled()) {
            throw new IllegalStateException("入库管道已禁用: " + pipeline.name());
        }
        return pipeline;
    }

    private IngestionService.SyncSummary runPipeline(
            IngestionPipelineDefinition pipeline,
            List<IngestionNodeLog> nodeLogs,
            Supplier<IngestionService.SyncSummary> syncSupplier,
            Supplier<Integer> sourceCountSupplier,
            Supplier<Integer> parsedCountSupplier,
            Supplier<Integer> enhancedCountSupplier,
            Supplier<Integer> chunkCountSupplier
    ) {
        IngestionExecutionContext context = new IngestionExecutionContext(syncSupplier, sourceCountSupplier, parsedCountSupplier, enhancedCountSupplier, chunkCountSupplier);
        for (IngestionNodeStage stage : pipeline.stages()) {
            IngestionStageNode node = stageNodeRegistry.require(stage);
            long stageStartedAt = System.currentTimeMillis();
            try {
                IngestionStageResult result = node.execute(context);
                long stageEndedAt = System.currentTimeMillis();
                nodeLogs.add(new IngestionNodeLog(stage, IngestionNodeStatus.SUCCESS, stageStartedAt, stageEndedAt, result.inputCount(), result.outputCount(), result.message(), result.details()));
            } catch (RuntimeException e) {
                long stageEndedAt = System.currentTimeMillis();
                int failedCount = context.getCurrentCount();
                nodeLogs.add(new IngestionNodeLog(
                        stage,
                        IngestionNodeStatus.FAILED,
                        stageStartedAt,
                        stageEndedAt,
                        failedCount,
                        failedCount,
                        e.getMessage() == null ? "unknown-error" : e.getMessage(),
                        Map.of()
                ));
                throw e;
            }
        }
        IngestionService.SyncSummary summary = context.getSummary();
        if (summary == null) {
            throw new IllegalStateException("入库管道缺少落库阶段: " + pipeline.name());
        }
        return summary;
    }

    private int countLocalSource(String vaultPath, List<String> ignoredDirs) {
        return noteLoader.loadNotes(vaultPath, ignoredDirs).size();
    }

    private int countParsedLocal(String vaultPath, List<String> ignoredDirs) {
        List<Resource> resources = noteLoader.loadNotes(vaultPath, ignoredDirs);
        int count = 0;
        for (Resource resource : resources) {
            try {
                String content = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
                String filePath = resource.getFile().getAbsolutePath();
                count += knowledgeExtractor.extract(content, filePath).documents().size();
            } catch (IOException e) {
                throw new IllegalStateException("统计 PARSE 阶段失败: " + resource.getFilename(), e);
            }
        }
        return count;
    }

    private int countChunkLocal(String vaultPath, List<String> ignoredDirs) {
        List<Resource> resources = noteLoader.loadNotes(vaultPath, ignoredDirs);
        int count = 0;
        for (Resource resource : resources) {
            try {
                String content = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
                String filePath = resource.getFile().getAbsolutePath();
                var extraction = knowledgeExtractor.extract(content, filePath);
                if (!extraction.isEmpty()) {
                    count += documentSplitter.split(extraction.documents()).size();
                }
            } catch (IOException e) {
                throw new IllegalStateException("统计 CHUNK 阶段失败: " + resource.getFilename(), e);
            }
        }
        return count;
    }

    private int countEnhancedLocal(String vaultPath, List<String> ignoredDirs) {
        return countParsedLocal(vaultPath, ignoredDirs);
    }

    private int countUploadSource(List<MultipartFile> files, List<String> relativePaths, List<String> ignoredDirs) {
        Set<String> ignoredSet = toIgnoredSet(ignoredDirs);
        return collectUploadCandidates(files, relativePaths, ignoredSet).size();
    }

    private int countParsedUpload(List<MultipartFile> files, List<String> relativePaths, List<String> ignoredDirs) {
        Set<String> ignoredSet = toIgnoredSet(ignoredDirs);
        List<UploadCandidate> candidates = collectUploadCandidates(files, relativePaths, ignoredSet);
        int count = 0;
        for (UploadCandidate candidate : candidates) {
            count += knowledgeExtractor.extract(candidate.content(), candidate.path()).documents().size();
        }
        return count;
    }

    private int countChunkUpload(List<MultipartFile> files, List<String> relativePaths, List<String> ignoredDirs) {
        Set<String> ignoredSet = toIgnoredSet(ignoredDirs);
        List<UploadCandidate> candidates = collectUploadCandidates(files, relativePaths, ignoredSet);
        int count = 0;
        for (UploadCandidate candidate : candidates) {
            var extraction = knowledgeExtractor.extract(candidate.content(), candidate.path());
            if (!extraction.isEmpty()) {
                count += documentSplitter.split(extraction.documents()).size();
            }
        }
        return count;
    }

    private int countEnhancedUpload(List<MultipartFile> files, List<String> relativePaths, List<String> ignoredDirs) {
        return countParsedUpload(files, relativePaths, ignoredDirs);
    }

    private List<UploadCandidate> collectUploadCandidates(List<MultipartFile> files, List<String> relativePaths, Set<String> ignoredSet) {
        List<UploadCandidate> candidates = new ArrayList<>();
        if (files == null || files.isEmpty()) {
            return candidates;
        }
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            String relativePath = relativePaths != null && i < relativePaths.size() ? relativePaths.get(i) : file.getOriginalFilename();
            if (relativePath == null || relativePath.isBlank()) {
                relativePath = file.getOriginalFilename();
            }
            if (relativePath == null) {
                continue;
            }
            String normalizedPath = relativePath.replace("\\", "/");
            if (!normalizedPath.toLowerCase().endsWith(".md")) {
                continue;
            }
            if (shouldIgnore(normalizedPath, ignoredSet)) {
                continue;
            }
            try {
                String content = new String(file.getBytes(), StandardCharsets.UTF_8);
                if (content.trim().isEmpty()) {
                    continue;
                }
                candidates.add(new UploadCandidate(normalizedPath, content));
            } catch (IOException e) {
                throw new IllegalStateException("读取上传文件失败: " + normalizedPath, e);
            }
        }
        return candidates;
    }

    private Set<String> toIgnoredSet(List<String> ignoredDirs) {
        if (ignoredDirs == null) {
            return Set.of();
        }
        return ignoredDirs.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .collect(Collectors.toSet());
    }

    private boolean shouldIgnore(String relativePath, Set<String> ignoredDirs) {
        if (ignoredDirs.isEmpty()) {
            return false;
        }
        String[] parts = relativePath.split("/");
        for (String part : parts) {
            if (ignoredDirs.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private void appendFallbackFailureIfAbsent(List<IngestionNodeLog> nodeLogs, long startedAt, long endedAt, String errorMessage) {
        if (!nodeLogs.isEmpty()) {
            return;
        }
        nodeLogs.add(new IngestionNodeLog(
                IngestionNodeStage.LEGACY_SYNC,
                IngestionNodeStatus.FAILED,
                startedAt,
                endedAt,
                0,
                0,
                errorMessage == null ? "unknown-error" : errorMessage,
                Map.of()
        ));
    }

    private String normalizeOptional(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    private record UploadCandidate(String path, String content) {
    }

    private static final class StageAggregate {
        private long totalDurationMs;
        private int invocations;
        private int failedInvocations;
    }
}
