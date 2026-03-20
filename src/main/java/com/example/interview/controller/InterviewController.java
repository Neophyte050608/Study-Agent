package com.example.interview.controller;

import com.example.interview.agent.a2a.A2ABus;
import com.example.interview.agent.a2a.A2AIdempotencyStore;
import com.example.interview.agent.a2a.RocketMqA2ABus;
import com.example.interview.agent.task.TaskResponse;
import com.example.interview.agent.task.TaskType;
import com.example.interview.core.InterviewSession;
import com.example.interview.service.AgentEvaluationService;
import com.example.interview.service.IngestionService;
import com.example.interview.service.InterviewService;
import com.example.interview.service.OpsAuditService;
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
    private static final long MAX_RESUME_SIZE_BYTES = 10L * 1024 * 1024;

    private final InterviewService interviewService;
    private final IngestionService ingestionService;
    private final UserIdentityResolver userIdentityResolver;
    private final OpsAuditService opsAuditService;
    private final AgentEvaluationService agentEvaluationService;
    private final A2ABus a2aBus;
    private final A2AIdempotencyStore a2AIdempotencyStore;

    public InterviewController(
            InterviewService interviewService,
            IngestionService ingestionService,
            UserIdentityResolver userIdentityResolver,
            OpsAuditService opsAuditService,
            AgentEvaluationService agentEvaluationService,
            A2ABus a2aBus,
            A2AIdempotencyStore a2AIdempotencyStore
    ) {
        this.interviewService = interviewService;
        this.ingestionService = ingestionService;
        this.userIdentityResolver = userIdentityResolver;
        this.opsAuditService = opsAuditService;
        this.agentEvaluationService = agentEvaluationService;
        this.a2aBus = a2aBus;
        this.a2AIdempotencyStore = a2AIdempotencyStore;
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
            IngestionService.SyncSummary summary = ingestionService.sync(path, ignoredList);
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
            return ResponseEntity.ok(Map.of("message", message));
        } catch (Exception e) {
            String message = e.getMessage() == null ? "同步失败，请检查配置" : e.getMessage();
            if (message.contains("401")) {
                message = "同步失败：Embedding API 认证失败，请检查智谱 API Key 是否有效";
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", message));
        }
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
            IngestionService.SyncSummary summary = ingestionService.syncUploadedNotes(files, relativePaths, folderName, ignoredList);
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
            return ResponseEntity.ok(Map.of("message", message));
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
        try {
            return ResponseEntity.ok(interviewService.submitAnswer(sessionId, answer));
        } catch (Exception e) {
            String message = e.getMessage() == null ? "回答分析失败，请稍后重试" : e.getMessage();
            String lowerMessage = message.toLowerCase();
            if (lowerMessage.contains("timeout")) {
                message = "回答分析超时，请稍后重试或简化回答后再试";
            }
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", message));
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
        try {
            return ResponseEntity.ok(interviewService.generateFinalReport(sessionId, userId));
        } catch (Exception e) {
            String message = e.getMessage() == null ? "总结生成失败，请稍后重试" : e.getMessage();
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", message));
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
        if (!context.containsKey("userId")) {
            // 兼容外部调用：如果未显式传业务 userId，则默认用 operator 作为 userId（便于画像归集）。
            context = new java.util.LinkedHashMap<>(context);
            context.put("userId", operator);
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
        return ResponseEntity.ok(interviewService.getRecentRagTraces(limit == null ? 20 : limit));
    }

    /**
     * 运行检索离线评测（默认用例）。
     */
    @GetMapping("/observability/retrieval-eval")
    public ResponseEntity<?> retrievalEval() {
        return ResponseEntity.ok(interviewService.runRetrievalOfflineEval());
    }

    /**
     * 运行带自定义用例的检索评测。
     */
    @PostMapping("/observability/retrieval-eval/run")
    public ResponseEntity<?> runRetrievalEval(@RequestBody Map<String, Object> payload) {
        List<RetrievalEvaluationService.EvalCase> cases = parseEvalCases(payload.get("cases"));
        if (cases.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "评测用例为空，请提供 cases"));
        }
        return ResponseEntity.ok(interviewService.runRetrievalEvalWithCases(cases));
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
            List<RetrievalEvaluationService.EvalCase> cases = interviewService.parseRetrievalEvalCsv(text);
            if (cases.isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "未解析到有效评测用例，请检查 CSV 格式"));
            }
            return ResponseEntity.ok(interviewService.runRetrievalEvalWithCases(cases));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "评测集文件读取失败"));
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
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "简历文件过大，请上传 10MB 以内 PDF"));
        }
        String originalName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!originalName.endsWith(".pdf")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "仅支持 PDF 简历"));
        }
        String contentType = file.getContentType();
        if (contentType != null && !contentType.equalsIgnoreCase("application/pdf")) {
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
                    return new RetrievalEvaluationService.EvalCase(query, keywords);
                })
                .filter(item -> item != null)
                .toList();
    }

    /**
     * 获取知识库同步统计数据。
     */
    @GetMapping("/observability/ingest/stats")
    public Map<String, Object> getIngestStats() {
        return ingestionService.getStats();
    }

    /**
     * 获取知识库同步配置。
     */
    @GetMapping("/ingest/config")
    public ResponseEntity<?> getIngestConfig() {
        try {
            Path path = Paths.get("sync_config.json");
            if (Files.exists(path)) {
                String content = Files.readString(path, StandardCharsets.UTF_8);
                return ResponseEntity.ok(new com.fasterxml.jackson.databind.ObjectMapper().readValue(content, Map.class));
            }
        } catch (Exception e) {
            logger.error("Read sync config error", e);
        }
        return ResponseEntity.ok(Map.of("paths", "", "ignoreDirs", ""));
    }

    /**
     * 保存知识库同步配置。
     */
    @PostMapping("/ingest/config")
    public ResponseEntity<?> saveIngestConfig(@RequestBody Map<String, String> payload) {
        try {
            Path path = Paths.get("sync_config.json");
            Files.writeString(path, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload), StandardCharsets.UTF_8);
            return ResponseEntity.ok(Map.of("message", "success"));
        } catch (Exception e) {
            logger.error("Save sync config error", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", "Save failed"));
        }
    }

    /**
     * 获取 RAG 概览指标。
     */
    @GetMapping("/observability/rag/overview")
    public Map<String, Object> getRagOverview() {
        return interviewService.getRagOverview();
    }
}
