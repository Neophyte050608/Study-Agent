package io.github.imzmq.interview.interview.application;

import io.github.imzmq.interview.config.observability.ObservabilitySwitchProperties;
import io.github.imzmq.interview.entity.knowledge.RagFeedbackDO;
import io.github.imzmq.interview.entity.knowledge.RagMetricsSnapshotDO;
import io.github.imzmq.interview.knowledge.application.observability.RAGObservabilityService;
import io.github.imzmq.interview.knowledge.application.evaluation.RAGQualityEvaluationService;
import io.github.imzmq.interview.knowledge.application.evaluation.RetrievalEvaluationService;
import io.github.imzmq.interview.mapper.knowledge.RagFeedbackMapper;
import io.github.imzmq.interview.mapper.knowledge.RagMetricsSnapshotMapper;
import io.github.imzmq.interview.skill.runtime.SkillTelemetryRecorder;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ObservabilityApplicationService {
    private static final int TRACE_FILTER_SCAN_LIMIT = 1_000;
    private static final int RETRIEVAL_METRICS_EVAL_SCAN_LIMIT = 200;

    private final RAGObservabilityService ragObservabilityService;
    private final RetrievalEvaluationService retrievalEvaluationService;
    private final RAGQualityEvaluationService ragQualityEvaluationService;
    private final ObservabilitySwitchProperties observabilitySwitchProperties;
    private final SkillTelemetryRecorder skillTelemetryRecorder;
    private final RagFeedbackMapper ragFeedbackMapper;
    private final RagMetricsSnapshotMapper ragMetricsSnapshotMapper;

    public ObservabilityApplicationService(RAGObservabilityService ragObservabilityService,
                                           RetrievalEvaluationService retrievalEvaluationService,
                                           RAGQualityEvaluationService ragQualityEvaluationService,
                                           ObservabilitySwitchProperties observabilitySwitchProperties,
                                           SkillTelemetryRecorder skillTelemetryRecorder,
                                           RagFeedbackMapper ragFeedbackMapper,
                                           RagMetricsSnapshotMapper ragMetricsSnapshotMapper) {
        this.ragObservabilityService = ragObservabilityService;
        this.retrievalEvaluationService = retrievalEvaluationService;
        this.ragQualityEvaluationService = ragQualityEvaluationService;
        this.observabilitySwitchProperties = observabilitySwitchProperties;
        this.skillTelemetryRecorder = skillTelemetryRecorder;
        this.ragFeedbackMapper = ragFeedbackMapper;
        this.ragMetricsSnapshotMapper = ragMetricsSnapshotMapper;
    }

    public List<RAGObservabilityService.TraceSummary> getRecentRagTraces(int limit) {
        return getRecentRagTraces(limit, null, false, false, false, false, null, null, null);
    }

    public List<RAGObservabilityService.TraceSummary> getRecentRagTraces(int limit,
                                                                         String status,
                                                                         boolean riskyOnly,
                                                                         boolean fallbackOnly,
                                                                         boolean emptyRetrievalOnly,
                                                                         boolean slowOnly,
                                                                         String query) {
        return getRecentRagTraces(limit, status, riskyOnly, fallbackOnly, emptyRetrievalOnly, slowOnly, query, null, null);
    }

    public List<RAGObservabilityService.TraceSummary> getRecentRagTraces(int limit,
                                                                         String status,
                                                                         boolean riskyOnly,
                                                                         boolean fallbackOnly,
                                                                         boolean emptyRetrievalOnly,
                                                                         boolean slowOnly,
                                                                         String query,
                                                                         Instant startedAfter,
                                                                         Instant endedBefore) {
        if (!observabilitySwitchProperties.isRagTraceEnabled()) {
            return List.of();
        }
        int effectiveLimit = limit <= 0 ? 20 : limit;
        int scanLimit = Math.max(effectiveLimit, TRACE_FILTER_SCAN_LIMIT);
        return ragObservabilityService.listRecentSummaries(scanLimit).stream()
                .filter(item -> matchesStatus(item, status))
                .filter(item -> !riskyOnly || item.riskCount() != null && item.riskCount() > 0)
                .filter(item -> !fallbackOnly || containsRisk(item, "fallback_triggered"))
                .filter(item -> !emptyRetrievalOnly || containsRisk(item, "retrieval_empty"))
                .filter(item -> !slowOnly || containsRisk(item, "slow_trace") || containsRisk(item, "slow_first_token"))
                .filter(item -> matchesQuery(item, query))
                .filter(item -> matchesTimeWindow(item, startedAfter, endedBefore))
                .limit(effectiveLimit)
                .toList();
    }

    public List<RAGObservabilityService.TraceSummary> getActiveRagTraces(int limit) {
        if (!observabilitySwitchProperties.isRagTraceEnabled()) {
            return List.of();
        }
        return ragObservabilityService.listActiveSummaries(limit);
    }

    public RAGObservabilityService.TraceDetailView getRagTraceDetail(String traceId) {
        if (!observabilitySwitchProperties.isRagTraceEnabled()) {
            return null;
        }
        return ragObservabilityService.getTraceDetailView(traceId);
    }

    public Map<String, Object> getRagOverview() {
        if (!observabilitySwitchProperties.isRagTraceEnabled()) {
            return Map.of("enabled", false, "avgLatencyMs", 0, "avgRetrievedDocs", 0.0, "cacheHitRate", "0.0%");
        }
        return ragObservabilityService.getOverview();
    }

    public Map<String, Object> getRetrievalMetrics(Integer limit, Integer hours, String dataset) {
        RAGObservabilityService.RetrievalLatencyStats latencyStats =
                ragObservabilityService.getRetrievalLatencyStats(limit, hours);
        RetrievalEvaluationService.RetrievalEvalRunSummary latestEvalRun = resolveLatestEvalRun(dataset);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("enabled", observabilitySwitchProperties.isRagTraceEnabled());
        response.put("retrievalEvalEnabled", observabilitySwitchProperties.isRetrievalEvalEnabled());
        response.put("top3Recall", latestEvalRun == null ? null : latestEvalRun.recallAt3());
        response.put("latestEvalRunId", latestEvalRun == null ? "" : latestEvalRun.runId());
        response.put("latestEvalTimestamp", latestEvalRun == null ? "" : latestEvalRun.timestamp());
        response.put("latestEvalDatasetSource", latestEvalRun == null ? "" : latestEvalRun.datasetSource());
        response.put("retrievalLatencyP95Ms", latencyStats.p95LatencyMs());
        response.put("retrievalLatencyP99Ms", latencyStats.p99LatencyMs());
        response.put("retrievalLatencySampleSize", latencyStats.sampleSize());

        Map<String, Object> window = new LinkedHashMap<>();
        window.put("mode", latencyStats.windowMode());
        if ("hours".equals(latencyStats.windowMode())) {
            window.put("hours", latencyStats.windowHours());
        } else if ("limit".equals(latencyStats.windowMode())) {
            window.put("limit", latencyStats.windowLimit());
        }
        response.put("window", window);

        List<String> messages = new ArrayList<>();
        if (!observabilitySwitchProperties.isRagTraceEnabled()) {
            messages.add("RAG Trace 已关闭，检索延迟指标不可用");
        } else if (latencyStats.sampleSize() == 0) {
            messages.add("暂无检索节点样本，无法计算 P95/P99");
        }
        if (!observabilitySwitchProperties.isRetrievalEvalEnabled()) {
            messages.add("检索评测已关闭，Top-3 召回率不可用");
        } else if (latestEvalRun == null) {
            String normalizedDataset = normalizeText(dataset);
            messages.add(normalizedDataset.isBlank()
                    ? "暂无检索评测结果，Top-3 召回率为空"
                    : "未找到指定数据集的检索评测结果: " + normalizedDataset);
        }
        response.put("message", messages.isEmpty() ? "ok" : String.join("；", messages));
        return response;
    }

    public RetrievalEvaluationService.RetrievalEvalReport runRetrievalOfflineEval() {
        return runRetrievalOfflineEval(null);
    }

    public RetrievalEvaluationService.RetrievalEvalReport runRetrievalOfflineEval(String dataset) {
        ensureRetrievalEvalEnabled();
        return retrievalEvaluationService.runEvalByDataset(dataset);
    }

    public RetrievalEvaluationService.RetrievalEvalReport runRetrievalEvalWithCases(List<RetrievalEvaluationService.EvalCase> cases) {
        ensureRetrievalEvalEnabled();
        return retrievalEvaluationService.runCustomEval(cases);
    }

    public RetrievalEvaluationService.RetrievalEvalReport runRetrievalEvalWithCases(
            List<RetrievalEvaluationService.EvalCase> cases,
            RetrievalEvaluationService.EvalRunOptions options
    ) {
        ensureRetrievalEvalEnabled();
        return retrievalEvaluationService.runCustomEval(cases, options);
    }

    public List<RetrievalEvaluationService.RetrievalEvalRunSummary> listRecentRetrievalEvalRuns(int limit) {
        ensureRetrievalEvalEnabled();
        return retrievalEvaluationService.listRecentRuns(limit);
    }

    public RetrievalEvaluationService.RetrievalEvalReport getRetrievalEvalRunDetail(String runId) {
        ensureRetrievalEvalEnabled();
        return retrievalEvaluationService.getRunDetail(runId);
    }

    public RetrievalEvaluationService.RetrievalEvalComparison compareRetrievalEvalRuns(String baselineRunId, String candidateRunId) {
        ensureRetrievalEvalEnabled();
        return retrievalEvaluationService.compareRuns(baselineRunId, candidateRunId);
    }

    public RetrievalEvaluationService.RetrievalEvalTrend getRetrievalEvalTrend(int limit) {
        ensureRetrievalEvalEnabled();
        return retrievalEvaluationService.getTrend(limit);
    }

    public List<RetrievalEvaluationService.RetrievalEvalFailureCluster> clusterRetrievalEvalFailures(String runId) {
        ensureRetrievalEvalEnabled();
        return retrievalEvaluationService.clusterFailures(runId);
    }

    public List<RetrievalEvaluationService.RetrievalEvalParameterTemplate> listRetrievalEvalParameterTemplates() {
        ensureRetrievalEvalEnabled();
        return retrievalEvaluationService.listParameterTemplates();
    }

    public List<RetrievalEvaluationService.EvalDatasetDefinition> listRetrievalEvalDatasets() {
        ensureRetrievalEvalEnabled();
        return retrievalEvaluationService.listBuiltInDatasets();
    }

    public List<RetrievalEvaluationService.EvalCase> parseRetrievalEvalCsv(String csvText) {
        ensureRetrievalEvalEnabled();
        return retrievalEvaluationService.parseCasesFromCsv(csvText);
    }

    public RAGQualityEvaluationService.QualityEvalReport runRagQualityEval(String engine) {
        return runRagQualityEval(null, engine);
    }

    public RAGQualityEvaluationService.QualityEvalReport runRagQualityEval(String dataset, String engine) {
        ensureRagQualityEvalEnabled();
        return ragQualityEvaluationService.runEvalByDataset(dataset, engine);
    }

    public RAGQualityEvaluationService.QualityEvalReport runRagQualityEvalWithCases(
            List<RAGQualityEvaluationService.QualityEvalCase> cases,
            RAGQualityEvaluationService.EvalRunOptions options,
            String engine
    ) {
        ensureRagQualityEvalEnabled();
        return ragQualityEvaluationService.runCustomEval(cases, options, engine);
    }

    public List<RAGQualityEvaluationService.QualityEvalRunSummary> listRecentRagQualityEvalRuns(int limit) {
        ensureRagQualityEvalEnabled();
        return ragQualityEvaluationService.listRecentRuns(limit);
    }

    public RAGQualityEvaluationService.QualityEvalReport getRagQualityEvalRunDetail(String runId) {
        ensureRagQualityEvalEnabled();
        return ragQualityEvaluationService.getRunDetail(runId);
    }

    public RAGQualityEvaluationService.QualityEvalComparison compareRagQualityEvalRuns(String baselineId, String candidateId) {
        ensureRagQualityEvalEnabled();
        return ragQualityEvaluationService.compareRuns(baselineId, candidateId);
    }

    public RAGQualityEvaluationService.QualityEvalTrend getRagQualityEvalTrend(int limit) {
        ensureRagQualityEvalEnabled();
        return ragQualityEvaluationService.getTrend(limit);
    }

    public Map<String, Object> getRagQualityEvalEngineStatus() {
        ensureRagQualityEvalEnabled();
        return ragQualityEvaluationService.getEngineStatus();
    }

    public List<RAGQualityEvaluationService.EvalDatasetDefinition> listRagQualityEvalDatasets() {
        ensureRagQualityEvalEnabled();
        return ragQualityEvaluationService.listBuiltInDatasets();
    }

    public boolean isRagTraceEnabled() {
        return observabilitySwitchProperties.isRagTraceEnabled();
    }

    public boolean isRetrievalEvalEnabled() {
        return observabilitySwitchProperties.isRetrievalEvalEnabled();
    }

    public boolean isRagQualityEvalEnabled() {
        return observabilitySwitchProperties.isRagQualityEvalEnabled();
    }

    public Map<String, Object> getObservabilitySwitches() {
        return Map.of(
                "ragTraceEnabled", observabilitySwitchProperties.isRagTraceEnabled(),
                "retrievalEvalEnabled", observabilitySwitchProperties.isRetrievalEvalEnabled(),
                "ragQualityEvalEnabled", observabilitySwitchProperties.isRagQualityEvalEnabled()
        );
    }

    public Map<String, Object> updateObservabilitySwitches(Boolean ragTraceEnabled, Boolean retrievalEvalEnabled, Boolean ragQualityEvalEnabled) {
        if (ragTraceEnabled != null) {
            observabilitySwitchProperties.setRagTraceEnabled(ragTraceEnabled);
        }
        if (retrievalEvalEnabled != null) {
            observabilitySwitchProperties.setRetrievalEvalEnabled(retrievalEvalEnabled);
        }
        if (ragQualityEvalEnabled != null) {
            observabilitySwitchProperties.setRagQualityEvalEnabled(ragQualityEvalEnabled);
        }
        return getObservabilitySwitches();
    }

    public List<SkillTelemetryRecorder.SkillTelemetryEvent> getRecentSkillTelemetry(int limit,
                                                                                    String skillId,
                                                                                    String status,
                                                                                    String traceId) {
        return skillTelemetryRecorder.recentEvents(limit, skillId, status, traceId);
    }

    private RetrievalEvaluationService.RetrievalEvalRunSummary resolveLatestEvalRun(String dataset) {
        if (!observabilitySwitchProperties.isRetrievalEvalEnabled()) {
            return null;
        }
        List<RetrievalEvaluationService.RetrievalEvalRunSummary> runs =
                retrievalEvaluationService.listRecentRuns(RETRIEVAL_METRICS_EVAL_SCAN_LIMIT);
        if (runs.isEmpty()) {
            return null;
        }
        String normalizedDataset = normalizeText(dataset);
        if (normalizedDataset.isBlank()) {
            return runs.get(0);
        }
        return runs.stream()
                .filter(run -> normalizedDataset.equalsIgnoreCase(normalizeText(run.datasetSource())))
                .findFirst()
                .orElse(null);
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    private void ensureRetrievalEvalEnabled() {
        if (!observabilitySwitchProperties.isRetrievalEvalEnabled()) {
            throw new IllegalStateException("召回率评测已关闭，请设置 app.observability.retrieval-eval-enabled=true 后重试");
        }
    }

    private void ensureRagQualityEvalEnabled() {
        if (!observabilitySwitchProperties.isRagQualityEvalEnabled()) {
            throw new IllegalStateException("RAG 生成质量评测已关闭，请设置 app.observability.rag-quality-eval-enabled=true 后重试");
        }
    }

    private boolean matchesStatus(RAGObservabilityService.TraceSummary item, String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return true;
        }
        String requested = status.trim().toUpperCase(Locale.ROOT);
        String traceStatus = item.traceStatus() == null ? "" : item.traceStatus().trim().toUpperCase(Locale.ROOT);
        String displayStatus = "COMPLETED".equals(traceStatus)
                ? (Boolean.TRUE.equals(item.slowTrace()) ? "SLOW" : "SUCCESS")
                : traceStatus;
        return requested.equals(displayStatus);
    }

    private boolean containsRisk(RAGObservabilityService.TraceSummary item, String riskTag) {
        return item.riskTags() != null && item.riskTags().contains(riskTag);
    }

    private boolean matchesQuery(RAGObservabilityService.TraceSummary item, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        String haystack = (item.traceId() == null ? "" : item.traceId()) + " "
                + String.join(" ", item.riskTags() == null ? List.of() : item.riskTags());
        return haystack.toLowerCase(Locale.ROOT).contains(normalized);
    }

    private boolean matchesTimeWindow(RAGObservabilityService.TraceSummary item, Instant startedAfter, Instant endedBefore) {
        Instant traceTime = item.endedAt() != null ? item.endedAt() : item.startedAt();
        if (traceTime == null) {
            return startedAfter == null && endedBefore == null;
        }
        if (startedAfter != null && traceTime.isBefore(startedAfter)) {
            return false;
        }
        if (endedBefore != null && traceTime.isAfter(endedBefore)) {
            return false;
        }
        return true;
    }

    public Map<String, Object> getDashboard() {
        if (!observabilitySwitchProperties.isRagTraceEnabled()) {
            return Map.of("enabled", false, "alertLevel", "NONE", "alertTags", List.of());
        }

        Map<String, Object> overview = ragObservabilityService.getOverview();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime hourStart = now.truncatedTo(ChronoUnit.HOURS);

        long thumbsUp = ragFeedbackMapper.selectCount(new LambdaQueryWrapper<RagFeedbackDO>()
                .eq(RagFeedbackDO::getFeedbackType, "THUMBS_UP")
                .ge(RagFeedbackDO::getCreatedAt, hourStart));
        long thumbsDown = ragFeedbackMapper.selectCount(new LambdaQueryWrapper<RagFeedbackDO>()
                .eq(RagFeedbackDO::getFeedbackType, "THUMBS_DOWN")
                .ge(RagFeedbackDO::getCreatedAt, hourStart));
        long copyCount = ragFeedbackMapper.selectCount(new LambdaQueryWrapper<RagFeedbackDO>()
                .eq(RagFeedbackDO::getFeedbackType, "COPY")
                .ge(RagFeedbackDO::getCreatedAt, hourStart));
        long feedbackTotal = thumbsUp + thumbsDown;
        double satisfactionRate = feedbackTotal > 0 ? (double) thumbsUp / feedbackTotal : 0.0;

        Map<String, Object> feedback = new LinkedHashMap<>();
        feedback.put("thumbsUp", thumbsUp);
        feedback.put("thumbsDown", thumbsDown);
        feedback.put("copy", copyCount);
        feedback.put("satisfactionRate", String.format(Locale.ROOT, "%.1f%%", satisfactionRate * 100));

        Map<String, Object> currentHour = new LinkedHashMap<>(overview);
        currentHour.put("feedback", feedback);

        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("enabled", true);
        dashboard.put("currentHour", currentHour);
        dashboard.put("alertLevel", overview.get("alertLevel"));
        dashboard.put("alertTags", overview.get("alertTags"));
        dashboard.put("trendBuckets", overview.get("trendBuckets"));
        dashboard.put("riskTagCounts", overview.get("riskTagCounts"));
        return dashboard;
    }

    public List<Map<String, Object>> getMetricsHistory(int hours, String metricsParam) {
        if (!observabilitySwitchProperties.isRagTraceEnabled() || hours <= 0) {
            return List.of();
        }
        int safeHours = Math.min(hours, 720);
        LocalDateTime cutoff = LocalDateTime.now().minusHours(safeHours);
        List<RagMetricsSnapshotDO> snapshots = ragMetricsSnapshotMapper.selectList(
                new LambdaQueryWrapper<RagMetricsSnapshotDO>()
                        .ge(RagMetricsSnapshotDO::getSnapshotHour, cutoff)
                        .orderByAsc(RagMetricsSnapshotDO::getSnapshotHour));

        Set<String> requestedMetrics = metricsParam == null || metricsParam.isBlank()
                ? Set.of("avgLatencyMs", "p95LatencyMs", "satisfactionRate")
                : Set.of(metricsParam.split(","));

        List<Map<String, Object>> result = new ArrayList<>();
        for (RagMetricsSnapshotDO snap : snapshots) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("hour", snap.getSnapshotHour().toString());
            if (requestedMetrics.contains("avgLatencyMs"))
                point.put("avgLatencyMs", snap.getAvgDurationMs());
            if (requestedMetrics.contains("p95LatencyMs"))
                point.put("p95LatencyMs", snap.getP95DurationMs());
            if (requestedMetrics.contains("satisfactionRate"))
                point.put("satisfactionRate", String.format(Locale.ROOT, "%.1f%%", snap.getSatisfactionRate() * 100));
            if (requestedMetrics.contains("fallbackRate")) {
                double fbRate = snap.getTraceCount() > 0
                        ? (double) snap.getFallbackCount() / snap.getTraceCount() * 100 : 0;
                point.put("fallbackRate", String.format(Locale.ROOT, "%.1f%%", fbRate));
            }
            if (requestedMetrics.contains("emptyRetrievalRate")) {
                double erRate = snap.getTraceCount() > 0
                        ? (double) snap.getEmptyRetrievalCount() / snap.getTraceCount() * 100 : 0;
                point.put("emptyRetrievalRate", String.format(Locale.ROOT, "%.1f%%", erRate));
            }
            if (requestedMetrics.contains("successRate")) {
                double sRate = snap.getTraceCount() > 0
                        ? (double) snap.getSuccessCount() / snap.getTraceCount() * 100 : 0;
                point.put("successRate", String.format(Locale.ROOT, "%.1f%%", sRate));
            }
            if (requestedMetrics.contains("traceCount"))
                point.put("traceCount", snap.getTraceCount());
            result.add(point);
        }
        return result;
    }

    public Map<String, Object> getMetricsSummary() {
        if (!observabilitySwitchProperties.isRagTraceEnabled()) {
            return Map.of("enabled", false);
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todayStart = now.truncatedTo(ChronoUnit.DAYS);
        LocalDateTime yesterdayStart = todayStart.minusDays(1);
        LocalDateTime yesterdayEnd = todayStart;
        LocalDateTime thisWeekStart = todayStart.minusDays(now.getDayOfWeek().getValue() % 7);
        LocalDateTime lastWeekStart = thisWeekStart.minusDays(7);
        LocalDateTime lastWeekEnd = thisWeekStart;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("today", buildPeriodSummary(todayStart, now));
        summary.put("yesterday", buildPeriodSummary(yesterdayStart, yesterdayEnd));
        summary.put("thisWeek", buildPeriodSummary(thisWeekStart, now));
        summary.put("lastWeek", buildPeriodSummary(lastWeekStart, lastWeekEnd));
        return summary;
    }

    private Map<String, Object> buildPeriodSummary(LocalDateTime from, LocalDateTime to) {
        List<RagMetricsSnapshotDO> snapshots = ragMetricsSnapshotMapper.selectList(
                new LambdaQueryWrapper<RagMetricsSnapshotDO>()
                        .ge(RagMetricsSnapshotDO::getSnapshotHour, from)
                        .lt(RagMetricsSnapshotDO::getSnapshotHour, to));

        int totalTraces = snapshots.stream().mapToInt(RagMetricsSnapshotDO::getTraceCount).sum();
        long totalDuration = snapshots.stream().mapToLong(s -> s.getAvgDurationMs() * s.getTraceCount()).sum();
        long avgLatency = totalTraces > 0 ? totalDuration / totalTraces : 0;
        long totalThumbsUp = snapshots.stream().mapToLong(RagMetricsSnapshotDO::getThumbsUpCount).sum();
        long totalThumbsDown = snapshots.stream().mapToLong(RagMetricsSnapshotDO::getThumbsDownCount).sum();
        long totalFeedback = totalThumbsUp + totalThumbsDown;
        double satRate = totalFeedback > 0 ? (double) totalThumbsUp / totalFeedback * 100 : 0;

        Map<String, Object> period = new LinkedHashMap<>();
        period.put("traceCount", totalTraces);
        period.put("avgLatencyMs", avgLatency);
        period.put("satisfactionRate", String.format(Locale.ROOT, "%.1f%%", satRate));
        return period;
    }
}






