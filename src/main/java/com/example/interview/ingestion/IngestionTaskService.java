package com.example.interview.ingestion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.interview.entity.IngestionTaskHistoryDO;
import com.example.interview.mapper.IngestionTaskHistoryMapper;
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

/**
 * 入库任务服务（任务化管道执行 + 观测快照维护）。
 *
 * <p>该服务负责将“知识入库”从传统的同步调用包装为可观测的任务执行模型：</p>
 * <ul>
 *     <li>根据 sourceType 选择启用的 {@link IngestionPipelineDefinition} 并按阶段串行执行</li>
 *     <li>为每个阶段生成 {@link IngestionNodeLog}，输出输入/输出规模、耗时与结构化 details</li>
 *     <li>在内存中维护最近若干次任务的 {@link IngestionTaskSnapshot}，提供列表/详情/统计聚合视图</li>
 * </ul>
 *
 * <p>注意：该服务不直接实现“向量/词法/图谱/增量标记”等落库细节，而是通过
 * {@link IngestionService} 的同步方法在提交阶段统一执行。</p>
 */
@Service
public class IngestionTaskService {

    /**
     * 内存中保留的最大历史任务数，超出后按最旧优先淘汰。
     */
    private static final int MAX_HISTORY = 100;
    /**
     * 本地目录入库的数据源类型标识（与配置中的 sourceType 对齐）。
     */
    private static final String SOURCE_TYPE_LOCAL = "LOCAL_VAULT";
    /**
     * 浏览器上传入库的数据源类型标识（与配置中的 sourceType 对齐）。
     */
    private static final String SOURCE_TYPE_UPLOAD = "BROWSER_UPLOAD";

    private final IngestionService ingestionService;
    private final IngestionPipelineRegistry pipelineRegistry;
    private final IngestionStageNodeRegistry stageNodeRegistry;
    private final NoteLoader noteLoader;
    private final ObsidianKnowledgeExtractor knowledgeExtractor;
    private final DocumentSplitter documentSplitter;
    private final IngestionTaskHistoryMapper ingestionTaskHistoryMapper;
    /**
     * 任务历史顺序队列：最近任务在前（addFirst），用于 listTasks 按时间倒序返回。
     */
    private final ConcurrentLinkedDeque<String> historyOrder = new ConcurrentLinkedDeque<>();
    /**
     * 任务快照存储：taskId -> snapshot。
     */
    private final ConcurrentMap<String, IngestionTaskSnapshot> snapshots = new ConcurrentHashMap<>();

    public IngestionTaskService(
            IngestionService ingestionService,
            IngestionPipelineRegistry pipelineRegistry,
            IngestionStageNodeRegistry stageNodeRegistry,
            NoteLoader noteLoader,
            ObsidianKnowledgeExtractor knowledgeExtractor,
            DocumentSplitter documentSplitter,
            IngestionTaskHistoryMapper ingestionTaskHistoryMapper
    ) {
        this.ingestionService = ingestionService;
        this.pipelineRegistry = pipelineRegistry;
        this.stageNodeRegistry = stageNodeRegistry;
        this.noteLoader = noteLoader;
        this.knowledgeExtractor = knowledgeExtractor;
        this.documentSplitter = documentSplitter;
        this.ingestionTaskHistoryMapper = ingestionTaskHistoryMapper;
    }

    /**
     * 执行“本地目录”入库任务。
     *
     * <p>该方法会生成 taskId 并按管道阶段执行，最终将任务快照保存到内存历史中。</p>
     *
     * @param vaultPath   本地知识库目录路径
     * @param ignoredDirs 需要忽略的目录名列表（按路径片段匹配）
     * @return 任务执行结果（包含 taskId、任务状态与同步摘要）
     */
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

    /**
     * 执行“浏览器上传”入库任务。
     *
     * <p>与本地目录入库不同：源数据来自 multipart 上传文件列表，路径由 relativePaths 决定；
     * 同样会按管道阶段执行并生成阶段日志。</p>
     *
     * @param files         上传的文件列表（通常为 Markdown）
     * @param relativePaths 每个文件对应的相对路径（用于还原目录结构），可为空
     * @param folderName    上传时选择的根目录名（用于构造虚拟文件路径前缀）
     * @param ignoredDirs   需要忽略的目录名列表（按路径片段匹配）
     * @return 任务执行结果（包含 taskId、任务状态与同步摘要）
     */
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

    /**
     * 列出最近的入库任务快照（按时间倒序）。
     *
     * @param limit 最大返回数量（会做边界保护）
     * @return 任务快照列表
     */
    public List<IngestionTaskSnapshot> listTasks(int limit) {
        return listTasks(limit, null, null);
    }

    /**
     * 列出最近任务并支持按 sourceType/status 过滤。
     *
     * <p>过滤使用大小写不敏感匹配；空值表示不启用该过滤条件。</p>
     *
     * @param limit     最大返回数量
     * @param sourceType 数据源类型过滤（可为空）
     * @param status     任务状态过滤（可为空）
     * @return 过滤后的任务快照列表
     */
    public List<IngestionTaskSnapshot> listTasks(int limit, String sourceType, String status) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_HISTORY));
        String normalizedSourceType = normalizeOptional(sourceType);
        String normalizedStatus = normalizeOptional(status);
        Map<String, IngestionTaskSnapshot> merged = new LinkedHashMap<>();
        List<IngestionTaskHistoryDO> historyList = queryHistory(safeLimit * 2, normalizedSourceType, normalizedStatus);
        for (IngestionTaskHistoryDO history : historyList) {
            IngestionTaskSnapshot snapshot = toSnapshot(history);
            merged.put(snapshot.taskId(), snapshot);
        }
        for (String taskId : historyOrder) {
            IngestionTaskSnapshot snapshot = snapshots.get(taskId);
            if (snapshot == null) {
                continue;
            }
            if (normalizedSourceType != null && !snapshot.sourceType().equalsIgnoreCase(normalizedSourceType)) {
                continue;
            }
            if (normalizedStatus != null && !snapshot.status().name().equalsIgnoreCase(normalizedStatus)) {
                continue;
            }
            merged.put(snapshot.taskId(), snapshot);
        }
        return merged.values().stream()
                .sorted(Comparator.comparingLong(IngestionTaskSnapshot::startedAt).reversed())
                .limit(safeLimit)
                .toList();
    }

    /**
     * 将任务快照转换为前端友好的视图结构（Map）。
     *
     * @param limit           最大返回数量
     * @param sourceType      数据源过滤
     * @param status          状态过滤
     * @param includeNodeLogs 是否包含 nodeLogs 原始数组（大字段，默认可关闭以降低负载）
     * @return 任务视图列表
     */
    public List<Map<String, Object>> listTaskViews(int limit, String sourceType, String status, boolean includeNodeLogs) {
        List<IngestionTaskSnapshot> snapshots = listTasks(limit, sourceType, status);
        List<Map<String, Object>> result = new ArrayList<>();
        for (IngestionTaskSnapshot snapshot : snapshots) {
            result.add(toTaskView(snapshot, includeNodeLogs));
        }
        return result;
    }

    /**
     * 根据 taskId 查询任务快照。
     *
     * @param taskId 任务 ID
     * @return 快照（可能不存在）
     */
    public Optional<IngestionTaskSnapshot> findTaskById(String taskId) {
        IngestionTaskSnapshot inMemory = snapshots.get(taskId);
        if (inMemory != null) {
            return Optional.of(inMemory);
        }
        IngestionTaskHistoryDO history = ingestionTaskHistoryMapper.selectOne(
                new LambdaQueryWrapper<IngestionTaskHistoryDO>().eq(IngestionTaskHistoryDO::getTaskId, taskId).last("LIMIT 1")
        );
        if (history == null) {
            return Optional.empty();
        }
        return Optional.of(toSnapshot(history));
    }

    /**
     * 根据 taskId 查询任务视图。
     *
     * @param taskId          任务 ID
     * @param includeNodeLogs 是否包含 nodeLogs
     * @return 任务视图（可能不存在）
     */
    public Optional<Map<String, Object>> findTaskViewById(String taskId, boolean includeNodeLogs) {
        return findTaskById(taskId).map(item -> toTaskView(item, includeNodeLogs));
    }

    public boolean hasRunningTasks() {
        for (IngestionTaskSnapshot snapshot : snapshots.values()) {
            if (snapshot.status() == IngestionTaskStatus.RUNNING || snapshot.status() == IngestionTaskStatus.PENDING) {
                return true;
            }
        }
        return false;
    }

    /**
     * 列出当前生效的入库管道定义。
     *
     * @return 管道列表
     */
    public List<IngestionPipelineDefinition> listPipelines() {
        return pipelineRegistry.listPipelines();
    }

    /**
     * 构建入库任务概览（默认参数）。
     *
     * @param recentLimit 最近任务数量
     * @return 概览视图
     */
    public Map<String, Object> buildOverview(int recentLimit) {
        return buildOverview(recentLimit, null, null, true);
    }

    /**
     * 构建入库任务概览（支持窗口/过滤/分组）。
     *
     * <p>该概览用于运维快速判断入库健康度：最近任务数量、成功/失败分布、热点阶段耗时与失败率等。</p>
     *
     * @param recentLimit     参与统计的最近任务上限
     * @param windowMinutes   仅统计最近 N 分钟内的任务（可为空）
     * @param sourceType      数据源过滤（可为空）
     * @param groupBySourceType 是否按 sourceType 分组输出热点阶段（用于对比本地/上传差异）
     * @return 概览视图 Map
     */
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

    /**
     * 将任务快照转为视图 Map。
     *
     * <p>该方法会额外构建：</p>
     * <ul>
     *     <li>stageOverview：每个阶段的耗时、输入输出、详情与耗时占比</li>
     *     <li>hotStages：基于当前任务的阶段耗时热点排序</li>
     * </ul>
     */
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

    /**
     * 构建阶段摘要列表（适合 UI 表格展示）。
     *
     * <p>会先计算总耗时用于生成每个阶段的 durationRatio，避免前端重复计算。</p>
     */
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

    /**
     * 统计任务集合的热点阶段（按总耗时倒序）。
     *
     * <p>输出字段包含总耗时、调用次数、平均耗时与失败率，便于快速定位瓶颈与高风险阶段。</p>
     */
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

    /**
     * 按 sourceType 分组构建热点阶段统计，便于对比不同数据源的瓶颈差异。
     */
    private Map<String, List<Map<String, Object>>> buildHotStagesBySourceType(List<IngestionTaskSnapshot> tasks, int topN) {
        Map<String, List<IngestionTaskSnapshot>> grouped = tasks.stream()
                .collect(Collectors.groupingBy(IngestionTaskSnapshot::sourceType, LinkedHashMap::new, Collectors.toList()));
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<IngestionTaskSnapshot>> entry : grouped.entrySet()) {
            result.put(entry.getKey(), buildHotStages(entry.getValue(), topN));
        }
        return result;
    }

    /**
     * 计算比率（0~1），并对除零做保护。
     */
    private double calculateRatio(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0d;
        }
        return (double) numerator / denominator;
    }

    /**
     * 保存任务快照并维护最近任务队列。
     *
     * <p>该方法确保：</p>
     * <ul>
     *     <li>同一个 taskId 在队列中只出现一次</li>
     *     <li>队列长度不会超过 MAX_HISTORY，超出会淘汰最旧任务并清理其快照</li>
     * </ul>
     */
    private void saveSnapshot(IngestionTaskSnapshot snapshot) {
        snapshots.put(snapshot.taskId(), snapshot);
        historyOrder.remove(snapshot.taskId());
        historyOrder.addFirst(snapshot.taskId());
        persistHistory(snapshot);
        while (historyOrder.size() > MAX_HISTORY) {
            String removedTaskId = historyOrder.pollLast();
            if (removedTaskId != null) {
                snapshots.remove(removedTaskId);
            }
        }
    }

    private void persistHistory(IngestionTaskSnapshot snapshot) {
        IngestionTaskHistoryDO existing = ingestionTaskHistoryMapper.selectOne(
                new LambdaQueryWrapper<IngestionTaskHistoryDO>().eq(IngestionTaskHistoryDO::getTaskId, snapshot.taskId()).last("LIMIT 1")
        );
        IngestionTaskHistoryDO target = existing == null ? new IngestionTaskHistoryDO() : existing;
        target.setTaskId(snapshot.taskId());
        target.setPipelineName(snapshot.pipelineName());
        target.setSourceType(snapshot.sourceType());
        target.setStatus(snapshot.status().name());
        target.setStartedAt(snapshot.startedAt());
        target.setEndedAt(snapshot.endedAt());
        target.setDurationMs(Math.max(0, snapshot.endedAt() - snapshot.startedAt()));
        target.setSummary(snapshot.summary());
        target.setErrorMessage(snapshot.errorMessage());
        if (existing == null) {
            ingestionTaskHistoryMapper.insert(target);
        } else {
            ingestionTaskHistoryMapper.updateById(target);
        }
    }

    private IngestionTaskSnapshot toSnapshot(IngestionTaskHistoryDO history) {
        IngestionTaskStatus status;
        try {
            status = IngestionTaskStatus.valueOf(history.getStatus());
        } catch (Exception e) {
            status = IngestionTaskStatus.FAILED;
        }
        return new IngestionTaskSnapshot(
                history.getTaskId(),
                history.getPipelineName(),
                history.getSourceType(),
                status,
                history.getStartedAt() == null ? 0L : history.getStartedAt(),
                history.getEndedAt() == null ? 0L : history.getEndedAt(),
                history.getSummary() == null ? Map.of() : history.getSummary(),
                List.of(),
                history.getErrorMessage()
        );
    }

    private List<IngestionTaskHistoryDO> queryHistory(int limit, String sourceType, String status) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_HISTORY));
        LambdaQueryWrapper<IngestionTaskHistoryDO> wrapper = new LambdaQueryWrapper<>();
        if (sourceType != null) {
            wrapper.eq(IngestionTaskHistoryDO::getSourceType, sourceType);
        }
        if (status != null) {
            wrapper.eq(IngestionTaskHistoryDO::getStatus, status.toUpperCase(Locale.ROOT));
        }
        wrapper.orderByDesc(IngestionTaskHistoryDO::getStartedAt);
        Page<IngestionTaskHistoryDO> page = new Page<>(1, safeLimit);
        return ingestionTaskHistoryMapper.selectPage(page, wrapper).getRecords();
    }

    /**
     * 根据同步摘要推导任务状态。
     *
     * <p>当前规则较简单：只要存在 failedFiles，就认为是 PARTIAL_SUCCESS；否则 SUCCESS。
     * 真正的“任务失败”由 executeLocal/executeUpload 捕获异常并设置 FAILED。</p>
     */
    private IngestionTaskStatus resolveTaskStatus(IngestionService.SyncSummary summary) {
        if (summary.failedFiles > 0) {
            return IngestionTaskStatus.PARTIAL_SUCCESS;
        }
        return IngestionTaskStatus.SUCCESS;
    }

    /**
     * 将同步摘要转换为可序列化的 Map（用于快照/接口回包）。
     */
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

    /**
     * 构建失败时的兜底摘要（避免前端处理 null）。
     */
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

    /**
     * 解析并校验“启用的管道定义”。
     *
     * <p>如果管道被禁用，会直接抛出异常，避免执行一个不完整/被关闭的链路。</p>
     */
    private IngestionPipelineDefinition resolveEnabledPipeline(String sourceType) {
        IngestionPipelineDefinition pipeline = pipelineRegistry.resolveRequiredBySource(sourceType);
        if (!pipeline.enabled()) {
            throw new IllegalStateException("入库管道已禁用: " + pipeline.name());
        }
        return pipeline;
    }

    /**
     * 执行管道阶段（串行）。
     *
     * <p>该方法是任务化入库的核心：为每个阶段记录开始/结束时间，捕获异常并写入失败节点日志。</p>
     *
     * <p>执行完成后必须产生 {@link IngestionService.SyncSummary}；如果缺少提交阶段导致 summary 为空，
     * 会抛出异常提示管道配置缺失。</p>
     */
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

    /**
     * 统计本地目录作为入库源的“源文件数量”。
     *
     * <p>该统计用于 FETCH 阶段输出，通常等价于过滤后的 Markdown 文件数。</p>
     */
    private int countLocalSource(String vaultPath, List<String> ignoredDirs) {
        return noteLoader.loadNotes(vaultPath, ignoredDirs).size();
    }

    /**
     * 统计本地目录的 PARSE 阶段输出数量（解析后的文档数）。
     */
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

    /**
     * 统计本地目录的 CHUNK 阶段输出数量（切块数）。
     *
     * <p>该统计会执行解析 + 切块，因此相对较重，建议只在任务一次执行中懒加载一次。</p>
     */
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

    /**
     * 统计本地目录的 ENHANCE 阶段输出数量。
     *
     * <p>当前实现与 PARSE 相同（增强不改变条数），保留该方法便于未来扩展真实增强逻辑。</p>
     */
    private int countEnhancedLocal(String vaultPath, List<String> ignoredDirs) {
        return countParsedLocal(vaultPath, ignoredDirs);
    }

    /**
     * 统计上传入库的源文件数量（过滤 .md + ignoredDirs + 空内容）。
     */
    private int countUploadSource(List<MultipartFile> files, List<String> relativePaths, List<String> ignoredDirs) {
        Set<String> ignoredSet = toIgnoredSet(ignoredDirs);
        return collectUploadCandidates(files, relativePaths, ignoredSet).size();
    }

    /**
     * 统计上传入库的 PARSE 阶段输出数量（解析后的文档数）。
     */
    private int countParsedUpload(List<MultipartFile> files, List<String> relativePaths, List<String> ignoredDirs) {
        Set<String> ignoredSet = toIgnoredSet(ignoredDirs);
        List<UploadCandidate> candidates = collectUploadCandidates(files, relativePaths, ignoredSet);
        int count = 0;
        for (UploadCandidate candidate : candidates) {
            count += knowledgeExtractor.extract(candidate.content(), candidate.path()).documents().size();
        }
        return count;
    }

    /**
     * 统计上传入库的 CHUNK 阶段输出数量（切块数）。
     */
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

    /**
     * 统计上传入库的 ENHANCE 阶段输出数量。
     *
     * <p>当前实现与 PARSE 相同（增强不改变条数）。</p>
     */
    private int countEnhancedUpload(List<MultipartFile> files, List<String> relativePaths, List<String> ignoredDirs) {
        return countParsedUpload(files, relativePaths, ignoredDirs);
    }

    /**
     * 收集上传入库的候选文件（只保留有效的 Markdown）。
     *
     * <p>过滤规则：</p>
     * <ul>
     *     <li>仅保留 .md 文件</li>
     *     <li>按路径片段过滤 ignoredDirs</li>
     *     <li>内容为空则跳过</li>
     * </ul>
     *
     * <p>返回的 path 会统一为使用 "/" 的相对路径，便于后续入库链路处理。</p>
     */
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

    /**
     * 将 ignoredDirs 规范化为 Set（去空、trim、去空白）。
     */
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

    /**
     * 判断一个相对路径是否应被忽略（按路径片段匹配）。
     *
     * <p>例如 relativePath = "foo/bar/a.md"，ignoredDirs 包含 "bar" 则会被忽略。</p>
     */
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

    /**
     * 兜底追加一条失败节点日志。
     *
     * <p>用于异常在“执行管道前”就发生的情况，确保任务仍能返回可展示的 nodeLogs，
     * 避免前端出现空数组导致体验不一致。</p>
     */
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

    /**
     * 规范化可选参数：空值/空白返回 null，其它统一 trim + upper。
     */
    private String normalizeOptional(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 上传文件候选：包含“规范化后的相对路径”和“文件内容”。
     */
    private record UploadCandidate(String path, String content) {
    }

    /**
     * 阶段聚合统计（用于热点阶段分析）。
     */
    private static final class StageAggregate {
        private long totalDurationMs;
        private int invocations;
        private int failedInvocations;
    }
}
