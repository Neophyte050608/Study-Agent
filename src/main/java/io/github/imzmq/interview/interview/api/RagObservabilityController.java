package io.github.imzmq.interview.interview.api;

import io.github.imzmq.interview.interview.api.support.ControllerHelper;
import io.github.imzmq.interview.interview.application.InterviewService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class RagObservabilityController {

    private final InterviewService interviewService;

    public RagObservabilityController(InterviewService interviewService) {
        this.interviewService = interviewService;
    }

    /**
     * 获取最近的 RAG (检索增强生成) 调用链路追踪。
     */
    @GetMapping("/observability/rag-traces")
    public ResponseEntity<?> ragTraces(@RequestParam(value = "limit", required = false, defaultValue = "20") Integer limit,
                                       @RequestParam(value = "status", required = false) String status,
                                       @RequestParam(value = "riskyOnly", required = false, defaultValue = "false") boolean riskyOnly,
                                       @RequestParam(value = "fallbackOnly", required = false, defaultValue = "false") boolean fallbackOnly,
                                       @RequestParam(value = "emptyRetrievalOnly", required = false, defaultValue = "false") boolean emptyRetrievalOnly,
                                       @RequestParam(value = "slowOnly", required = false, defaultValue = "false") boolean slowOnly,
                                       @RequestParam(value = "q", required = false) String query,
                                       @RequestParam(value = "startedAfter", required = false) Instant startedAfter,
                                       @RequestParam(value = "endedBefore", required = false) Instant endedBefore) {
        if (!interviewService.isRagTraceEnabled()) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(interviewService.getRecentRagTraces(
                limit == null ? 20 : limit,
                status,
                riskyOnly,
                fallbackOnly,
                emptyRetrievalOnly,
                slowOnly,
                query,
                startedAfter,
                endedBefore
        ));
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
        Boolean ragTraceEnabled = ControllerHelper.parseBooleanFlag(payload.get("ragTraceEnabled"));
        Boolean retrievalEvalEnabled = ControllerHelper.parseBooleanFlag(payload.get("retrievalEvalEnabled"));
        Boolean ragQualityEvalEnabled = ControllerHelper.parseBooleanFlag(payload.get("ragQualityEvalEnabled"));
        if (ragTraceEnabled == null && retrievalEvalEnabled == null && ragQualityEvalEnabled == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "请至少提供一个开关字段"));
        }
        return ResponseEntity.ok(interviewService.updateObservabilitySwitches(ragTraceEnabled, retrievalEvalEnabled, ragQualityEvalEnabled));
    }

    /**
     * 获取 RAG 概览指标。
     */
    @GetMapping("/observability/rag/overview")
    public Map<String, Object> getRagOverview() {
        return interviewService.getRagOverview();
    }

    @GetMapping("/observability/rag/dashboard")
    public Map<String, Object> getRagDashboard() {
        return interviewService.getRagDashboard();
    }

    @GetMapping("/observability/rag/metrics/history")
    public ResponseEntity<?> getMetricsHistory(
            @RequestParam(value = "hours", required = false, defaultValue = "168") Integer hours,
            @RequestParam(value = "metric", required = false) String metric) {
        return ResponseEntity.ok(interviewService.getMetricsHistory(
                hours == null ? 168 : hours, metric));
    }

    @GetMapping("/observability/rag/metrics/summary")
    public Map<String, Object> getMetricsSummary() {
        return interviewService.getMetricsSummary();
    }

    @PostMapping("/observability/rag/metrics/snapshot/trigger")
    public Map<String, Object> triggerMetricsSnapshot() {
        return interviewService.triggerMetricsSnapshot();
    }
}
