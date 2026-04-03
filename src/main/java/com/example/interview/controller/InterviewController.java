package com.example.interview.controller;

import com.example.interview.agent.a2a.A2ABus;
import com.example.interview.agent.a2a.A2AIdempotencyStore;
import com.example.interview.agent.a2a.RocketMqA2ABus;
import com.example.interview.agent.task.TaskResponse;
import com.example.interview.agent.task.TaskType;
import com.example.interview.core.InterviewSession;
import com.example.interview.core.RAGTraceContext;
import com.example.interview.ingestion.IngestionTaskExecutionResult;
import com.example.interview.ingestion.IngestionTaskService;
import com.example.interview.service.AgentEvaluationService;
import com.example.interview.service.IngestConfigService;
import com.example.interview.service.IngestionService;
import com.example.interview.service.InterviewService;
import com.example.interview.service.OpsAuditService;
import com.example.interview.service.RAGQualityEvaluationService;
import com.example.interview.service.RetrievalEvaluationService;
import com.example.interview.service.UserIdentityResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 面试系统 HTTP 入口层。
 * 负责参数解析、基础校验、错误码映射，并把业务请求转发到服务层。
 *
 * <p>接口类别：</p>
 * <ul>
 *   <li>面试主流程：/start /answer /report</li>
 *   <li>通用任务路由：/task/dispatch（对外统一入口，内部再路由到各 Agent）</li>
 *   <li>知识库同步：/ingest /ingest-files</li>
 *   <li>MCP 工具能力：/mcp/capabilities /mcp/invoke</li>
 *   <li>可观测/运维：/observability/**（用于调试与追踪，不建议对公网暴露）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class InterviewController {

    private static final Logger logger = LoggerFactory.getLogger(InterviewController.class);
    private static final long MAX_RESUME_SIZE_BYTES = 20L * 1024 * 1024;

    private final InterviewService interviewService;
    private final IngestionService ingestionService;
    private final IngestionTaskService ingestionTaskService;
    private final UserIdentityResolver userIdentityResolver;
    private final OpsAuditService opsAuditService;
    private final AgentEvaluationService agentEvaluationService;
    private final RetrievalEvaluationService retrievalEvaluationService;
    private final A2ABus a2aBus;
    private final A2AIdempotencyStore a2AIdempotencyStore;
    private final IngestConfigService ingestConfigService;

    public InterviewController(
            InterviewService interviewService,
            IngestionService ingestionService,
            IngestionTaskService ingestionTaskService,
            UserIdentityResolver userIdentityResolver,
            OpsAuditService opsAuditService,
            AgentEvaluationService agentEvaluationService,
            RetrievalEvaluationService retrievalEvaluationService,
            A2ABus a2aBus,
            A2AIdempotencyStore a2AIdempotencyStore,
            IngestConfigService ingestConfigService
    ) {
        this.interviewService = interviewService;
        this.ingestionService = ingestionService;
        this.ingestionTaskService = ingestionTaskService;
        this.userIdentityResolver = userIdentityResolver;
        this.opsAuditService = opsAuditService;
        this.agentEvaluationService = agentEvaluationService;
        this.retrievalEvaluationService = retrievalEvaluationService;
        this.a2aBus = a2aBus;
        this.a2AIdempotencyStore = a2AIdempotencyStore;
        this.ingestConfigService = ingestConfigService;
    }

    /**
     * 同步本地知识库到向量库。
     *
     * @param payload 包含 path (笔记目录) 和 ignoreDirs (忽略目录，逗号分隔)
     * @return 同步结果摘要
     */
    @PostMapping("/ingest")
    public ResponseEntity<Map<String, String>> ingest(@RequestBody Map<String, String> payload) {
        // 同步本地知识库到向量库：path 指向笔记/资料目录，ignoreDirs 用逗号分隔过滤目录名。
        String path = payload.get("path");
        String ignoreDirs = payload.get("ignoreDirs");
        List<String> ignoredList = parseIgnoreDirs(ignoreDirs);
        try {
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
            return ResponseEntity.ok(Map.of(
                    "message", message,
                    "taskId", executionResult.taskId(),
                    "taskStatus", executionResult.status().name()
            ));
        } catch (Exception e) {
            String message = e.getMessage() == null ? "同步失败，请检查配置" : e.getMessage();
            if (message.contains("401")) {
                message = "同步失败：Embedding API 认证失败，请检查智谱 API Key 是否有效";
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", message));
        }
    }

    @PostMapping("/ingestion/reindex/parent-child")
    public ResponseEntity<Map<String, Object>> forceReindexParentChild(@RequestBody Map<String, String> payload) {
        String path = payload.get("path");
        String ignoreDirs = payload.get("ignoreDirs");
        List<String> ignoredList = parseIgnoreDirs(ignoreDirs);
        try {
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
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            String message = e.getMessage() == null ? "Parent-Child 重建失败，请检查参数与数据源" : e.getMessage();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", message));
        }
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
    public ResponseEntity<Map<String, String>> ingestFiles(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "relativePaths", required = false) List<String> relativePaths,
            @RequestParam(value = "folderName", required = false) String folderName,
            @RequestParam(value = "ignoreDirs", required = false) String ignoreDirs) {
        // 上传笔记文件后同步：支持保留相对路径（relativePaths）以恢复目录结构。
        try {
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
            return ResponseEntity.ok(Map.of(
                    "message", message,
                    "taskId", executionResult.taskId(),
                    "taskStatus", executionResult.status().name()
            ));
        } catch (Exception e) {
            String message = e.getMessage() == null ? "同步失败，请检查文件内容" : e.getMessage();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", message));
        }
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

    /**
     * 创建一轮新的模拟面试会话，并返回首题。
     *
     * @param payload 包含 topic (面试主题), resumePath (简历路径), totalQuestions (总题数)
     * @param request HTTP 请求，用于解析用户身份
     * @return 新建的面试会话对象
     */
    @PostMapping("/start")
    public InterviewSession startSession(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
        String userId = userIdentityResolver.resolve(request);
        String topic = payload.get("topic") == null ? "" : payload.get("topic").toString();
        String resumePath = payload.get("resumePath") == null ? "" : payload.get("resumePath").toString();
        String traceId = payload.get("traceId") == null ? UUID.randomUUID().toString() : payload.get("traceId").toString();

        RAGTraceContext.setTraceId(traceId);
        try {
            Integer totalQuestions = null;
            Object rawTotal = payload.get("totalQuestions");
            if (rawTotal instanceof Number) {
                totalQuestions = ((Number) rawTotal).intValue();
            } else if (rawTotal instanceof String) {
                String raw = (String) rawTotal;
                if (!raw.isBlank()) {
                    try {
                        totalQuestions = Integer.parseInt(raw.trim());
                    } catch (NumberFormatException ignored) {
                        totalQuestions = null;
                    }
                }
            }
            return interviewService.startSession(userId, topic, resumePath, totalQuestions);
        } finally {
            RAGTraceContext.clear();
        }
    }

    /**
     * 提交当前题目的作答文本，返回评分、反馈和下一题。
     *
     * @param payload 包含 sessionId 和 answer
     * @return 答题分析结果及下一题
     */
    @PostMapping("/answer")
    public ResponseEntity<?> submitAnswer(@RequestBody Map<String, String> payload) {
        String sessionId = payload.get("sessionId");
        String answer = payload.get("answer");
        String traceId = payload.getOrDefault("traceId", UUID.randomUUID().toString());

        RAGTraceContext.setTraceId(traceId);
        try {
            return ResponseEntity.ok(interviewService.submitAnswer(sessionId, answer));
        } catch (Exception e) {
            String message = e.getMessage() == null ? "回答分析失败，请稍后重试" : e.getMessage();
            String lowerMessage = message.toLowerCase();
            if (lowerMessage.contains("timeout")) {
                message = "回答分析超时，请稍后重试或简化回答后再试";
            }
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", message));
        } finally {
            RAGTraceContext.clear();
        }
    }

    /**
     * 结束后生成整场面试总结报告。
     *
     * @param payload 包含 sessionId
     * @param request HTTP 请求，用于解析用户身份
     * @return 最终面试报告
     */
    @PostMapping("/report")
    public ResponseEntity<?> report(@RequestBody Map<String, String> payload, HttpServletRequest request) {
        String sessionId = payload.get("sessionId");
        String userId = userIdentityResolver.resolve(request);
        String traceId = payload.getOrDefault("traceId", UUID.randomUUID().toString());

        RAGTraceContext.setTraceId(traceId);
        try {
            return ResponseEntity.ok(interviewService.generateFinalReport(sessionId, userId));
        } catch (Exception e) {
            String message = e.getMessage() == null ? "总结生成失败，请稍后重试" : e.getMessage();
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", message));
        } finally {
            RAGTraceContext.clear();
        }
    }

    /**
     * 通用任务分发接口。
     * 将请求路由到不同的 Agent 处理（如学习路径规划、代码练习生成等）。
     *
     * @param payload 包含 taskType, payload (任务参数), context (上下文信息)
     * @param request HTTP 请求，用于解析操作者身份
     * @return 任务处理响应
     */
    @PostMapping("/task/dispatch")
    public ResponseEntity<?> dispatchTask(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
        // operator 表示“本次调用的操作者”，用于审计；context.userId 表示“业务主体用户”，可与 operator 不同。
        String operator = userIdentityResolver.resolve(request);
        String taskTypeRaw = payload.get("taskType") == null ? "" : payload.get("taskType").toString();
        if (taskTypeRaw.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "taskType 不能为空"));
        }
        TaskType taskType;
        try {
            taskType = TaskType.valueOf(taskTypeRaw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "不支持的 taskType: " + taskTypeRaw));
        }
        Map<String, Object> taskPayload = payload.get("payload") instanceof Map<?, ?> map
                ? map.entrySet().stream().collect(Collectors.toMap(
                entry -> String.valueOf(entry.getKey()),
                Map.Entry::getValue,
                (left, right) -> right
        ))
                : Map.of();
        Map<String, Object> context = payload.get("context") instanceof Map<?, ?> map
                ? map.entrySet().stream().collect(Collectors.toMap(
                entry -> String.valueOf(entry.getKey()),
                Map.Entry::getValue,
                (left, right) -> right
        ))
                : Map.of();

        String traceId = context.get("traceId") == null ? UUID.randomUUID().toString() : context.get("traceId").toString();
        RAGTraceContext.setTraceId(traceId);

        try {
            if (!context.containsKey("userId")) {
                // 兼容外部调用：如果未显式传业务 userId，则默认用 operator 作为 userId（便于画像归集）。
                context = new java.util.LinkedHashMap<>(context);
                context.put("userId", operator);
            }
            // 确保 context 中包含 traceId
            if (!context.containsKey("traceId")) {
                context = new java.util.LinkedHashMap<>(context);
                context.put("traceId", traceId);
            }

            TaskResponse response = interviewService.dispatchTask(taskType, taskPayload, context);
            opsAuditService.record(
                    operator,
                    "TASK_DISPATCH",
                    Map.of("taskType", taskType.name(), "contextUserId", String.valueOf(context.get("userId"))),
                    response.success(),
                    response.message()
            );
            if (response.success()) {
                return ResponseEntity.ok(response);
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        } finally {
            RAGTraceContext.clear();
        }
    }

    /**
     * 获取 MCP (Model Context Protocol) 工具能力列表。
     *
     * @param traceId 可选的调用链路 ID
     * @param request HTTP 请求
     * @return 包含用户 ID、链路 ID 和能力列表的响应
     */
    @GetMapping("/mcp/capabilities")
    public ResponseEntity<?> mcpCapabilities(
            @RequestParam(value = "traceId", required = false) String traceId,
            HttpServletRequest request
    ) {
        // 能力发现支持显式 traceId，便于将调用与后续 invoke 关联。
        String userId = userIdentityResolver.resolve(request);
        String resolvedTraceId = traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId.trim();
        return ResponseEntity.ok(Map.of(
                "userId", userId,
                "traceId", resolvedTraceId,
                "capabilities", interviewService.discoverMcpCapabilities(userId, resolvedTraceId)
        ));
    }

    /**
     * 调用指定的 MCP 工具能力。
     *
     * @param payload 包含 capability (能力名), params (入参), context (上下文)
     * @param request HTTP 请求
     * @return 工具调用结果
     */
    @PostMapping("/mcp/invoke")
    public ResponseEntity<?> invokeMcp(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
        // MCP 调用入口：capability 对应某个工具能力名；params 为工具入参；context 用于链路与身份透传。
        String userId = userIdentityResolver.resolve(request);
        String capability = payload.get("capability") == null ? "" : payload.get("capability").toString().trim();
        if (capability.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "capability 不能为空"));
        }
        Map<String, Object> params = payload.get("params") instanceof Map<?, ?> map
                ? map.entrySet().stream().collect(Collectors.toMap(
                e -> String.valueOf(e.getKey()),
                Map.Entry::getValue,
                (left, right) -> right,
                java.util.LinkedHashMap::new
        ))
                : Map.of();
        Map<String, Object> context = payload.get("context") instanceof Map<?, ?> map
                ? map.entrySet().stream().collect(Collectors.toMap(
                e -> String.valueOf(e.getKey()),
                Map.Entry::getValue,
                (left, right) -> right,
                java.util.LinkedHashMap::new
        ))
                : Map.of();
        if (!context.containsKey("traceId")) {
            // 若上游未透传 traceId，自动补齐以保证观测链路完整。
            context = new java.util.LinkedHashMap<>(context);
            context.put("traceId", UUID.randomUUID().toString());
        }
        Map<String, Object> result = interviewService.invokeMcpCapability(userId, capability, params, context);
        return ResponseEntity.ok(result);
    }

    /**
     * 获取指定主题的能力掌握曲线。
     */
    @GetMapping("/profile/topic-capability")
    public ResponseEntity<?> topicCapability(@RequestParam("topic") String topic, HttpServletRequest request) {
        String userId = userIdentityResolver.resolve(request);
        return ResponseEntity.ok(interviewService.getTopicCapabilityCurve(userId, topic));
    }

    /**
     * 获取用户画像总览。
     */
    @GetMapping("/profile/overview")
    public ResponseEntity<?> profileOverview(HttpServletRequest request) {
        String userId = userIdentityResolver.resolve(request);
        return ResponseEntity.ok(interviewService.getProfileOverview(userId));
    }

    /**
     * 获取个性化学习/面试建议。
     *
     * @param mode 模式（如 interview 或 learning）
     * @param request HTTP 请求
     * @return 建议内容
     */
    @GetMapping("/profile/recommendations")
    public ResponseEntity<?> profileRecommendations(
            @RequestParam(value = "mode", required = false, defaultValue = "interview") String mode,
            HttpServletRequest request
    ) {
        String userId = userIdentityResolver.resolve(request);
        return ResponseEntity.ok(Map.of(
                "mode", mode,
                "recommendation", interviewService.getProfileRecommendation(userId, mode)
        ));
    }

    /**
     * 获取最近的 RAG (检索增强生成) 调用链路追踪。
     */
    @GetMapping("/observability/rag-traces")
    public ResponseEntity<?> ragTraces(@RequestParam(value = "limit", required = false, defaultValue = "20") Integer limit) {
        if (!interviewService.isRagTraceEnabled()) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(interviewService.getRecentRagTraces(limit == null ? 20 : limit));
    }

    /**
     * 获取运行中的 RAG Trace 列表（活动态）。
     */
    @GetMapping("/observability/rag-traces/active")
    public ResponseEntity<?> ragTracesActive(@RequestParam(value = "limit", required = false, defaultValue = "20") Integer limit) {
        if (!interviewService.isRagTraceEnabled()) {
            return ResponseEntity.ok(List.of());
        }
        int normalizedLimit = limit == null ? 20 : limit;
        return ResponseEntity.ok(interviewService.getActiveRagTraces(normalizedLimit));
    }

    /**
     * 获取单条 RAG Trace 的完整节点详情。
     */
    @GetMapping("/observability/rag-traces/{traceId}")
    public ResponseEntity<?> ragTraceDetail(@PathVariable("traceId") String traceId) {
        if (!interviewService.isRagTraceEnabled()) {
            return ResponseEntity.ok(Map.of());
        }
        var trace = interviewService.getRagTraceDetail(traceId);
        if (trace == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "未找到对应的 RAG Trace"));
        }
        return ResponseEntity.ok(trace);
    }

    @GetMapping("/observability/switches")
    public ResponseEntity<?> observabilitySwitches() {
        return ResponseEntity.ok(interviewService.getObservabilitySwitches());
    }

    @PutMapping("/observability/switches")
    public ResponseEntity<?> updateObservabilitySwitches(@RequestBody Map<String, Object> payload) {
        Boolean ragTraceEnabled = parseBooleanFlag(payload.get("ragTraceEnabled"));
        Boolean retrievalEvalEnabled = parseBooleanFlag(payload.get("retrievalEvalEnabled"));
        Boolean ragQualityEvalEnabled = parseBooleanFlag(payload.get("ragQualityEvalEnabled"));
        if (ragTraceEnabled == null && retrievalEvalEnabled == null && ragQualityEvalEnabled == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "请至少提供一个开关字段"));
        }
        return ResponseEntity.ok(interviewService.updateObservabilitySwitches(ragTraceEnabled, retrievalEvalEnabled, ragQualityEvalEnabled));
    }

    /**
     * 运行检索离线评测（默认用例）。
     */
    @GetMapping("/observability/retrieval-eval")
    public ResponseEntity<?> retrievalEval() {
        try {
            return ResponseEntity.ok(interviewService.runRetrievalOfflineEval());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 查询最近的检索评测运行历史。
     */
    @GetMapping("/observability/retrieval-eval/runs")
    public ResponseEntity<?> listRetrievalEvalRuns(
            @RequestParam(value = "limit", required = false, defaultValue = "20") Integer limit
    ) {
        try {
            int normalizedLimit = limit == null ? 20 : limit;
            return ResponseEntity.ok(Map.of(
                    "limit", normalizedLimit,
                    "records", interviewService.listRecentRetrievalEvalRuns(normalizedLimit)
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 查询单次检索评测详情。
     */
    @GetMapping("/observability/retrieval-eval/runs/{runId}")
    public ResponseEntity<?> getRetrievalEvalRunDetail(@PathVariable("runId") String runId) {
        try {
            RetrievalEvaluationService.RetrievalEvalReport report = interviewService.getRetrievalEvalRunDetail(runId);
            if (report == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "未找到对应的检索评测运行"));
            }
            return ResponseEntity.ok(report);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 对比两次检索评测运行结果。
     */
    @GetMapping("/observability/retrieval-eval/compare")
    public ResponseEntity<?> compareRetrievalEvalRuns(
            @RequestParam("baselineRunId") String baselineRunId,
            @RequestParam("candidateRunId") String candidateRunId
    ) {
        try {
            RetrievalEvaluationService.RetrievalEvalComparison comparison = interviewService.compareRetrievalEvalRuns(baselineRunId, candidateRunId);
            if (comparison == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "未找到可对比的检索评测运行"));
            }
            return ResponseEntity.ok(comparison);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 查询检索评测趋势摘要。
     */
    @GetMapping("/observability/retrieval-eval/trend")
    public ResponseEntity<?> getRetrievalEvalTrend(
            @RequestParam(value = "limit", required = false, defaultValue = "20") Integer limit
    ) {
        try {
            int normalizedLimit = limit == null ? 20 : limit;
            return ResponseEntity.ok(interviewService.getRetrievalEvalTrend(normalizedLimit));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 查询指定评测运行的失败样本聚类结果。
     */
    @GetMapping("/observability/retrieval-eval/runs/{runId}/failure-clusters")
    public ResponseEntity<?> getRetrievalEvalFailureClusters(@PathVariable("runId") String runId) {
        try {
            List<RetrievalEvaluationService.RetrievalEvalFailureCluster> clusters = interviewService.clusterRetrievalEvalFailures(runId);
            if (clusters == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "未找到对应的检索评测运行"));
            }
            return ResponseEntity.ok(Map.of(
                    "runId", runId,
                    "clusters", clusters
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 获取检索评测参数模板列表。
     */
    @GetMapping("/observability/retrieval-eval/templates")
    public ResponseEntity<?> listRetrievalEvalTemplates() {
        try {
            return ResponseEntity.ok(Map.of(
                    "records", interviewService.listRetrievalEvalParameterTemplates()
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 运行带自定义用例的检索评测。
     */
    @PostMapping("/observability/retrieval-eval/run")
    public ResponseEntity<?> runRetrievalEval(@RequestBody Map<String, Object> payload) {
        try {
            List<RetrievalEvaluationService.EvalCase> cases = parseEvalCases(payload.get("cases"));
            if (cases.isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "评测用例为空，请提供 cases"));
            }
            RetrievalEvaluationService.EvalRunOptions options = new RetrievalEvaluationService.EvalRunOptions(
                    stringifyValue(payload.get("datasetSource")),
                    stringifyValue(payload.get("runLabel")),
                    stringifyValue(payload.get("experimentTag")),
                    extractParameterSnapshot(payload.get("parameterSnapshot")),
                    stringifyValue(payload.get("notes"))
            );
            return ResponseEntity.ok(interviewService.runRetrievalEvalWithCases(cases, options));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 上传 CSV 评测集并运行检索评测。
     */
    @PostMapping("/observability/retrieval-eval/upload")
    public ResponseEntity<?> uploadRetrievalEval(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "请上传评测集文件"));
        }
        try {
            String text = new String(file.getBytes(), StandardCharsets.UTF_8);
            List<RetrievalEvaluationService.EvalCase> cases = retrievalEvaluationService.parseCasesFromCsv(text);
            return ResponseEntity.ok(retrievalEvaluationService.runCustomEval(
                    cases,
                    new RetrievalEvaluationService.EvalRunOptions("csv", "csv-upload", "", Map.of(), "CSV 上传评测")
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "评测集文件读取失败"));
        }
    }

    /**
     * 运行 RAG 生成质量评测（默认数据集）。
     */
    @GetMapping("/observability/rag-quality-eval")
    public ResponseEntity<?> runRagQualityEval() {
        try {
            return ResponseEntity.ok(interviewService.runRAGQualityEval());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 运行自定义数据集 RAG 生成质量评测。
     */
    @PostMapping("/observability/rag-quality-eval/run")
    public ResponseEntity<?> runRagQualityEvalWithCases(@RequestBody Map<String, Object> payload) {
        try {
            List<RAGQualityEvaluationService.QualityEvalCase> cases = parseQualityEvalCases(payload.get("cases"));
            if (cases.isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "评测用例为空，请提供 cases"));
            }

            Object rawOptions = payload.get("options");
            String datasetSource = stringifyValue(payload.get("datasetSource"));
            String runLabel = stringifyValue(payload.get("runLabel"));
            String experimentTag = stringifyValue(payload.get("experimentTag"));
            Map<String, Object> parameterSnapshot = extractParameterSnapshot(payload.get("parameterSnapshot"));
            String notes = stringifyValue(payload.get("notes"));
            if (rawOptions instanceof Map<?, ?> map) {
                datasetSource = stringifyValue(map.get("datasetSource"));
                runLabel = stringifyValue(map.get("runLabel"));
                experimentTag = stringifyValue(map.get("experimentTag"));
                parameterSnapshot = extractParameterSnapshot(map.get("parameterSnapshot"));
                notes = stringifyValue(map.get("notes"));
            }

            RAGQualityEvaluationService.EvalRunOptions options = new RAGQualityEvaluationService.EvalRunOptions(
                    datasetSource,
                    runLabel,
                    experimentTag,
                    parameterSnapshot,
                    notes
            );
            return ResponseEntity.ok(interviewService.runRAGQualityEvalWithCases(cases, options));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 查询 RAG 生成质量评测历史列表。
     */
    @GetMapping("/observability/rag-quality-eval/runs")
    public ResponseEntity<?> listRagQualityEvalRuns(
            @RequestParam(value = "limit", required = false, defaultValue = "20") Integer limit
    ) {
        try {
            int normalizedLimit = limit == null ? 20 : limit;
            return ResponseEntity.ok(Map.of(
                    "limit", normalizedLimit,
                    "records", interviewService.listRecentRAGQualityEvalRuns(normalizedLimit)
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 查询单次 RAG 生成质量评测详情。
     */
    @GetMapping("/observability/rag-quality-eval/runs/{runId}")
    public ResponseEntity<?> getRagQualityEvalRunDetail(@PathVariable("runId") String runId) {
        try {
            RAGQualityEvaluationService.QualityEvalReport report = interviewService.getRAGQualityEvalRunDetail(runId);
            if (report == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "未找到对应的RAG生成质量评测运行"));
            }
            return ResponseEntity.ok(report);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 对比两次 RAG 生成质量评测运行结果。
     */
    @GetMapping("/observability/rag-quality-eval/compare")
    public ResponseEntity<?> compareRagQualityEvalRuns(
            @RequestParam("baselineRunId") String baselineRunId,
            @RequestParam("candidateRunId") String candidateRunId
    ) {
        try {
            RAGQualityEvaluationService.QualityEvalComparison comparison =
                    interviewService.compareRAGQualityEvalRuns(baselineRunId, candidateRunId);
            if (comparison == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "未找到可对比的RAG生成质量评测运行"));
            }
            return ResponseEntity.ok(comparison);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 查询 RAG 生成质量评测趋势摘要。
     */
    @GetMapping("/observability/rag-quality-eval/trend")
    public ResponseEntity<?> getRagQualityEvalTrend(
            @RequestParam(value = "limit", required = false, defaultValue = "20") Integer limit
    ) {
        try {
            int normalizedLimit = limit == null ? 20 : limit;
            return ResponseEntity.ok(interviewService.getRAGQualityEvalTrend(normalizedLimit));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * 获取 A2A 幂等存储状态快照。
     */
    @GetMapping("/observability/a2a/idempotency")
    public ResponseEntity<?> a2aIdempotency() {
        return ResponseEntity.ok(a2AIdempotencyStore.snapshot());
    }

    /**
     * 获取最近的操作审计记录。
     */
    @GetMapping("/observability/audit/ops")
    public ResponseEntity<?> opsAudit(
            @RequestParam(value = "limit", required = false, defaultValue = "20") Integer limit
    ) {
        return ResponseEntity.ok(Map.of(
                "limit", limit == null ? 20 : limit,
                "records", opsAuditService.listRecent(limit == null ? 20 : limit)
        ));
    }

    /**
     * 获取 MCP 工具调用日志，支持按链路 ID 和错误码过滤。
     */
    @GetMapping("/observability/mcp/logs")
    public ResponseEntity<?> mcpAudit(
            @RequestParam(value = "limit", required = false, defaultValue = "20") Integer limit,
            @RequestParam(value = "traceId", required = false) String traceId,
            @RequestParam(value = "errorCode", required = false) String errorCode
    ) {
        int normalizedLimit = limit == null ? 20 : limit;
        return ResponseEntity.ok(Map.of(
                "limit", normalizedLimit,
                "traceId", traceId == null ? "" : traceId,
                "errorCode", errorCode == null ? "" : errorCode,
                "records", opsAuditService.listFiltered(normalizedLimit, "MCP_", traceId, errorCode)
        ));
    }

    /**
     * 获取画像更新事件流。
     */
    @GetMapping("/observability/profile/events")
    public ResponseEntity<?> profileEvents(
            @RequestParam(value = "limit", required = false, defaultValue = "20") Integer limit,
            HttpServletRequest request
    ) {
        String userId = userIdentityResolver.resolve(request);
        int normalizedLimit = limit == null ? 20 : limit;
        return ResponseEntity.ok(Map.of(
                "userId", userId,
                "limit", normalizedLimit,
                "events", interviewService.getProfileEvents(userId, normalizedLimit)
        ));
    }

    /**
     * 运行 Agent 自动化评测试验。
     */
    @PostMapping("/observability/agent-eval/trials")
    public ResponseEntity<?> runAgentTrial(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
        String operator = userIdentityResolver.resolve(request);
        Map<String, Object> result = agentEvaluationService.runTrial(operator, payload == null ? Map.of() : payload);
        opsAuditService.record(
                operator,
                "AGENT_EVAL_TRIAL_RUN",
                Map.of(
                        "trialId", String.valueOf(result.getOrDefault("trialId", "")),
                        "agent", String.valueOf(result.getOrDefault("agent", ""))
                ),
                true,
                "ok"
        );
        return ResponseEntity.ok(result);
    }

    /**
     * 列出 Agent 评测试验记录。
     */
    @GetMapping("/observability/agent-eval/trials")
    public ResponseEntity<?> listAgentTrials(
            @RequestParam(value = "limit", required = false, defaultValue = "20") Integer limit,
            @RequestParam(value = "agent", required = false) String agent,
            @RequestParam(value = "status", required = false) String status
    ) {
        int normalized = limit == null ? 20 : limit;
        return ResponseEntity.ok(Map.of(
                "limit", normalized,
                "agent", agent == null ? "" : agent,
                "status", status == null ? "" : status,
                "records", agentEvaluationService.listTrials(normalized, agent, status)
        ));
    }

    /**
     * 获取单个 Agent 评测试验详情。
     */
    @GetMapping("/observability/agent-eval/trials/{trialId}")
    public ResponseEntity<?> getAgentTrial(@PathVariable("trialId") String trialId) {
        Map<String, Object> result = agentEvaluationService.getTrial(trialId);
        if (result.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "trial 不存在"));
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 获取 Agent 评测试验的对话详情（Transcript）。
     */
    @GetMapping("/observability/agent-eval/transcripts/{trialId}")
    public ResponseEntity<?> getAgentTranscript(@PathVariable("trialId") String trialId) {
        Map<String, Object> transcript = agentEvaluationService.getTranscript(trialId);
        if (transcript.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "trial 不存在"));
        }
        return ResponseEntity.ok(transcript);
    }

    /**
     * 列出所有可用的 Agent 评分器。
     */
    @GetMapping("/observability/agent-eval/scorers")
    public ResponseEntity<?> listAgentScorers() {
        return ResponseEntity.ok(Map.of(
                "records", agentEvaluationService.listScorers()
        ));
    }

    /**
     * 手动重放 A2A 死信队列（DLQ）消息。
     * 仅用于排障。
     */
    @PostMapping("/observability/a2a/dlq/replay")
    public ResponseEntity<?> replayDlq(@RequestBody Map<String, String> payload, HttpServletRequest request) {
        // 仅用于排障：把 DLQ 中的一条原始 JSON 消息反序列化后重新投递进 A2A 总线。
        String operator = userIdentityResolver.resolve(request);
        String rawMessage = payload.get("message");
        if (rawMessage == null || rawMessage.isBlank()) {
            opsAuditService.record(operator, "A2A_DLQ_REPLAY", Map.of("messagePresent", false), false, "message 不能为空");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "message 不能为空"));
        }
        if (!(a2aBus instanceof RocketMqA2ABus rocketMqA2ABus)) {
            opsAuditService.record(operator, "A2A_DLQ_REPLAY", Map.of("messagePresent", true), false, "当前非 rocketmq 总线");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "当前非 rocketmq 总线，无法重放 DLQ"));
        }
        boolean ok = rocketMqA2ABus.dispatchInbound(rawMessage);
        if (!ok) {
            opsAuditService.record(operator, "A2A_DLQ_REPLAY", Map.of("messagePresent", true), false, "DLQ 消息格式无效或解析失败");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "DLQ 消息格式无效或解析失败"));
        }
        opsAuditService.record(operator, "A2A_DLQ_REPLAY", Map.of("messagePresent", true), true, "DLQ 消息已重放");
        return ResponseEntity.ok(Map.of("message", "DLQ 消息已重放"));
    }

    /**
     * 批量重放 A2A 死信队列消息。
     */
    @PostMapping("/observability/a2a/dlq/replay/batch")
    public ResponseEntity<?> replayDlqBatch(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
        // 批量重放：返回失败索引，便于定位哪条消息格式有问题。
        String operator = userIdentityResolver.resolve(request);
        if (!(a2aBus instanceof RocketMqA2ABus rocketMqA2ABus)) {
            opsAuditService.record(operator, "A2A_DLQ_REPLAY_BATCH", Map.of("count", 0), false, "当前非 rocketmq 总线");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "当前非 rocketmq 总线，无法重放 DLQ"));
        }
        if (!(payload.get("messages") instanceof List<?> list) || list.isEmpty()) {
            opsAuditService.record(operator, "A2A_DLQ_REPLAY_BATCH", Map.of("count", 0), false, "messages 不能为空");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "messages 不能为空"));
        }
        int success = 0;
        List<Integer> failedIndexes = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            String raw = item == null ? "" : item.toString();
            boolean ok = rocketMqA2ABus.dispatchInbound(raw);
            if (ok) {
                success++;
            } else {
                failedIndexes.add(i);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", list.size());
        result.put("success", success);
        result.put("failed", list.size() - success);
        result.put("failedIndexes", failedIndexes);
        opsAuditService.record(
                operator,
                "A2A_DLQ_REPLAY_BATCH",
                Map.of("count", list.size(), "failed", failedIndexes.size()),
                true,
                "batch_replayed"
        );
        return ResponseEntity.ok(result);
    }

    /**
     * 清理 A2A 幂等键。
     *
     * @param payload 包含 scope (memory|redis|all) 和 keyContains (可选的关键字匹配)
     */
    @PostMapping("/observability/a2a/idempotency/clear")
    public ResponseEntity<?> clearA2AIdempotency(@RequestBody Map<String, String> payload, HttpServletRequest request) {
        // 运维接口：清理幂等键（memory/redis/all），仅建议在排障或测试环境使用。
        String operator = userIdentityResolver.resolve(request);
        String scope = payload.get("scope");
        if (scope != null && !scope.isBlank()) {
            String normalized = scope.trim().toLowerCase();
            if (!normalized.equals("memory") && !normalized.equals("redis") && !normalized.equals("all")) {
                opsAuditService.record(operator, "A2A_IDEMPOTENCY_CLEAR", Map.of("scope", normalized), false, "scope 非法");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "scope 仅支持 memory|redis|all"));
            }
        }
        String keyContains = payload.get("keyContains");
        Map<String, Object> result = a2AIdempotencyStore.clear(scope, keyContains);
        opsAuditService.record(
                operator,
                "A2A_IDEMPOTENCY_CLEAR",
                Map.of("scope", scope == null ? "" : scope, "keyContains", keyContains == null ? "" : keyContains),
                true,
                "cleared"
        );
        return ResponseEntity.ok(result);
    }

    /**
     * 上传 PDF 简历。
     * 仅保留最近一次上传的简历。
     */
    @PostMapping("/resume/upload")
    public ResponseEntity<?> uploadResume(@RequestParam("file") MultipartFile file) {
        // 上传简历：做大小/扩展名/Content-Type 三重校验，减少非 PDF 内容带来的解析风险。
        if (file.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "请上传 PDF 简历文件"));
        }
        if (file.getSize() > MAX_RESUME_SIZE_BYTES) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "简历文件过大，请上传 20MB 以内 PDF"));
        }
        String originalName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!originalName.endsWith(".pdf")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "仅支持 PDF 简历"));
        }
        String contentType = file.getContentType();
        if (contentType != null && !contentType.isBlank()
                && !contentType.equalsIgnoreCase("application/pdf")
                && !contentType.equalsIgnoreCase("application/octet-stream")
                && !contentType.toLowerCase().startsWith("application/pdf")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "文件类型不是有效 PDF"));
        }
        try {
            Path uploadDir = Paths.get("uploads", "resumes");
            
            // 如果目录已存在，则清理里面旧的简历文件
            if (Files.exists(uploadDir)) {
                try (java.util.stream.Stream<Path> stream = Files.list(uploadDir)) {
                    stream.filter(path -> path.toString().toLowerCase().endsWith(".pdf"))
                          .forEach(path -> {
                              try {
                                  Files.deleteIfExists(path);
                              } catch (IOException e) {
                                  logger.warn("Failed to delete old resume: {}", path, e);
                              }
                          });
                }
            } else {
                Files.createDirectories(uploadDir);
            }
            
            String fileName = UUID.randomUUID() + ".pdf";
            Path target = uploadDir.resolve(fileName).normalize();
            try (var inputStream = file.getInputStream()) {
                Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return ResponseEntity.ok(Map.of(
                    "resumePath", target.toAbsolutePath().toString(),
                    "fileName", file.getOriginalFilename() == null ? "resume.pdf" : file.getOriginalFilename()
            ));
        } catch (IOException e) {
            logger.error("Resume upload failed", e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", "简历上传失败，请稍后重试"));
        }
    }

    /**
     * 解析评测用例对象。
     */
    private List<RetrievalEvaluationService.EvalCase> parseEvalCases(Object rawCases) {
        if (!(rawCases instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(item -> {
                    if (!(item instanceof Map<?, ?> map)) {
                        return null;
                    }
                    Object queryObj = map.get("query");
                    String query = queryObj == null ? "" : queryObj.toString().trim();
                    if (query.isBlank()) {
                        return null;
                    }
                    Object keywordsObj = map.get("expectedKeywords");
                    List<String> keywords;
                    if (keywordsObj instanceof List<?> keyList) {
                        keywords = keyList.stream()
                                .map(Object::toString)
                                .map(String::trim)
                                .filter(word -> !word.isBlank())
                                .toList();
                    } else {
                        keywords = List.of();
                    }
                    String tag = map.get("tag") == null ? "manual" : String.valueOf(map.get("tag")).trim();
                    return new RetrievalEvaluationService.EvalCase(query, keywords, tag.isBlank() ? "manual" : tag);
                })
                .filter(item -> item != null)
                .toList();
    }

    /**
     * 解析 RAG 生成质量评测用例对象。
     */
    private List<RAGQualityEvaluationService.QualityEvalCase> parseQualityEvalCases(Object rawCases) {
        if (!(rawCases instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(item -> {
                    if (!(item instanceof Map<?, ?> map)) {
                        return null;
                    }
                    String query = stringifyValue(map.get("query"));
                    if (query == null || query.isBlank()) {
                        return null;
                    }
                    String groundTruthAnswer = stringifyValue(map.get("groundTruthAnswer"));
                    Object rawConcepts = map.get("groundTruthKeyConcepts");
                    List<String> concepts;
                    if (rawConcepts instanceof List<?> conceptList) {
                        concepts = conceptList.stream()
                                .map(Object::toString)
                                .map(String::trim)
                                .filter(word -> !word.isBlank())
                                .toList();
                    } else {
                        concepts = List.of();
                    }
                    String tag = stringifyValue(map.get("tag"));
                    return new RAGQualityEvaluationService.QualityEvalCase(
                            query.trim(),
                            groundTruthAnswer == null ? "" : groundTruthAnswer,
                            concepts,
                            tag == null || tag.isBlank() ? "manual" : tag
                    );
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 将参数快照提取为字符串键的 Map，便于统一落库存档。
     */
    private Map<String, Object> extractParameterSnapshot(Object rawParameterSnapshot) {
        if (!(rawParameterSnapshot instanceof Map<?, ?> map)) {
            return Map.of();
        }
        return map.entrySet().stream().collect(Collectors.toMap(
                entry -> String.valueOf(entry.getKey()),
                Map.Entry::getValue,
                (left, right) -> right,
                LinkedHashMap::new
        ));
    }

    /**
     * 统一将可选字段转换为字符串，避免 null 与空白值污染评测元数据。
     */
    private String stringifyValue(Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        String text = String.valueOf(rawValue).trim();
        return text.isEmpty() ? null : text;
    }

    private Boolean parseBooleanFlag(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Boolean value) {
            return value;
        }
        String text = raw.toString().trim().toLowerCase();
        if ("true".equals(text) || "1".equals(text) || "on".equals(text) || "yes".equals(text)) {
            return true;
        }
        if ("false".equals(text) || "0".equals(text) || "off".equals(text) || "no".equals(text)) {
            return false;
        }
        return null;
    }

    /**
     * 获取知识库同步统计数据。
     */
    @GetMapping("/observability/ingest/stats")
    public Map<String, Object> getIngestStats(
            @RequestParam(value = "windowMinutes", required = false) Integer windowMinutes,
            @RequestParam(value = "sourceType", required = false) String sourceType,
            @RequestParam(value = "groupBySourceType", required = false, defaultValue = "true") boolean groupBySourceType
    ) {
        Map<String, Object> base = new LinkedHashMap<>(ingestionService.getStats());
        Map<String, Object> overview = ingestionTaskService.buildOverview(10, windowMinutes, sourceType, groupBySourceType);
        base.putAll(overview);
        Object recentTasksObj = overview.get("recentTasks");
        if (recentTasksObj instanceof List<?> recentTasks) {
            List<Map<String, Object>> reports = new ArrayList<>();
            for (Object obj : recentTasks) {
                if (!(obj instanceof Map<?, ?> map)) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                Object fileName = map.get("pipelineName");
                Object taskStatus = map.get("status");
                Object message = map.get("errorMessage");
                Object endedAt = map.get("endedAt");
                Object startedAt = map.get("startedAt");
                item.put("fileName", fileName == null ? "" : String.valueOf(fileName));
                item.put("status", taskStatus == null ? "" : String.valueOf(taskStatus));
                item.put("message", message == null ? "" : String.valueOf(message));
                item.put("timestamp", endedAt == null ? (startedAt == null ? System.currentTimeMillis() : startedAt) : endedAt);
                reports.add(item);
            }
            base.put("recentReports", reports);
        }
        return base;
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
                "ignoreDirs", saved.get("ignoreDirs")
        ));
    }

    /**
     * 获取 RAG 概览指标。
     */
    @GetMapping("/observability/rag/overview")
    public Map<String, Object> getRagOverview() {
        return interviewService.getRagOverview();
    }
}
