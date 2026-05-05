package io.github.imzmq.interview.interview.api;

import io.github.imzmq.interview.agent.a2a.A2ABus;
import io.github.imzmq.interview.agent.a2a.A2AIdempotencyStore;
import io.github.imzmq.interview.agent.a2a.RocketMqA2ABus;
import io.github.imzmq.interview.agent.application.AgentEvaluationService;
import io.github.imzmq.interview.identity.application.UserIdentityResolver;
import io.github.imzmq.interview.ingestion.application.IngestionService;
import io.github.imzmq.interview.ingestion.application.IngestionTaskService;
import io.github.imzmq.interview.interview.application.InterviewService;
import io.github.imzmq.interview.mcp.application.OpsAuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class InfraObservabilityController {

    private final InterviewService interviewService;
    private final UserIdentityResolver userIdentityResolver;
    private final OpsAuditService opsAuditService;
    private final AgentEvaluationService agentEvaluationService;
    private final A2ABus a2aBus;
    private final A2AIdempotencyStore a2AIdempotencyStore;
    private final IngestionService ingestionService;
    private final IngestionTaskService ingestionTaskService;

    public InfraObservabilityController(
            InterviewService interviewService,
            UserIdentityResolver userIdentityResolver,
            OpsAuditService opsAuditService,
            AgentEvaluationService agentEvaluationService,
            A2ABus a2aBus,
            A2AIdempotencyStore a2AIdempotencyStore,
            IngestionService ingestionService,
            IngestionTaskService ingestionTaskService
    ) {
        this.interviewService = interviewService;
        this.userIdentityResolver = userIdentityResolver;
        this.opsAuditService = opsAuditService;
        this.agentEvaluationService = agentEvaluationService;
        this.a2aBus = a2aBus;
        this.a2AIdempotencyStore = a2AIdempotencyStore;
        this.ingestionService = ingestionService;
        this.ingestionTaskService = ingestionTaskService;
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
     * 获取最近的技能执行 telemetry，支持按技能、状态、trace 过滤。
     */
    @GetMapping("/observability/skills")
    public ResponseEntity<?> skillTelemetry(
            @RequestParam(value = "limit", required = false, defaultValue = "20") Integer limit,
            @RequestParam(value = "skillId", required = false) String skillId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "traceId", required = false) String traceId
    ) {
        int normalizedLimit = limit == null ? 20 : limit;
        return ResponseEntity.ok(Map.of(
                "limit", normalizedLimit,
                "skillId", skillId == null ? "" : skillId,
                "status", status == null ? "" : status,
                "traceId", traceId == null ? "" : traceId,
                "records", interviewService.getRecentSkillTelemetry(normalizedLimit, skillId, status, traceId)
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
}
