package io.github.imzmq.interview.interview.application;

import io.github.imzmq.interview.config.observability.ObservabilitySwitchProperties;
import io.github.imzmq.interview.knowledge.application.contract.evaluation.RagQualityEvaluationOperations;
import io.github.imzmq.interview.knowledge.application.contract.evaluation.RetrievalEvaluationOperations;
import io.github.imzmq.interview.observability.application.contract.ObservabilityQueryOperations;
import io.github.imzmq.interview.observability.application.contract.query.RetrievalLatencyStats;
import io.github.imzmq.interview.observability.application.contract.query.TraceDetailView;
import io.github.imzmq.interview.observability.application.contract.query.TraceSummary;
import io.github.imzmq.interview.skill.runtime.SkillTelemetryRecorder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ObservabilityApplicationService {
    private static final int RETRIEVAL_METRICS_EVAL_SCAN_LIMIT = 200;

    private final RetrievalEvaluationOperations retrievalEvaluationService;
    private final RagQualityEvaluationOperations ragQualityEvaluationService;
    private final ObservabilitySwitchProperties observabilitySwitchProperties;
    private final SkillTelemetryRecorder skillTelemetryRecorder;
    private final ObservabilityQueryOperations observabilityQueryOperations;

    public ObservabilityApplicationService(RetrievalEvaluationOperations retrievalEvaluationService,
                                           RagQualityEvaluationOperations ragQualityEvaluationService,
                                           ObservabilitySwitchProperties observabilitySwitchProperties,
                                           SkillTelemetryRecorder skillTelemetryRecorder,
                                           ObservabilityQueryOperations observabilityQueryOperations) {
        this.retrievalEvaluationService = retrievalEvaluationService;
        this.ragQualityEvaluationService = ragQualityEvaluationService;
        this.observabilitySwitchProperties = observabilitySwitchProperties;
        this.skillTelemetryRecorder = skillTelemetryRecorder;
        this.observabilityQueryOperations = observabilityQueryOperations;
    }

    public List<TraceSummary> getRecentRagTraces(int limit) {
        return observabilityQueryOperations.getRecentRagTraces(limit);
    }

    public List<TraceSummary> getRecentRagTraces(int limit,
                                                                         String status,
                                                                         boolean riskyOnly,
                                                                         boolean fallbackOnly,
                                                                         boolean emptyRetrievalOnly,
                                                                         boolean slowOnly,
                                                                         String query) {
        return observabilityQueryOperations.getRecentRagTraces(
                limit, status, riskyOnly, fallbackOnly, emptyRetrievalOnly, slowOnly, query);
    }

    public List<TraceSummary> getRecentRagTraces(int limit,
                                                                         String status,
                                                                         boolean riskyOnly,
                                                                         boolean fallbackOnly,
                                                                         boolean emptyRetrievalOnly,
                                                                         boolean slowOnly,
                                                                         String query,
                                                                         Instant startedAfter,
                                                                         Instant endedBefore) {
        return observabilityQueryOperations.getRecentRagTraces(
                limit, status, riskyOnly, fallbackOnly, emptyRetrievalOnly, slowOnly,
                query, startedAfter, endedBefore);
    }

    public List<TraceSummary> getActiveRagTraces(int limit) {
        return observabilityQueryOperations.getActiveRagTraces(limit);
    }

    public TraceDetailView getRagTraceDetail(String traceId) {
        return observabilityQueryOperations.getRagTraceDetail(traceId);
    }

    public Map<String, Object> getRagOverview() {
        return observabilityQueryOperations.getRagOverview();
    }

    public Map<String, Object> getRetrievalMetrics(Integer limit, Integer hours, String dataset) {
        RetrievalLatencyStats latencyStats =
                observabilityQueryOperations.getRetrievalLatencyStats(limit, hours);
        RetrievalEvaluationOperations.RetrievalEvalRunSummary latestEvalRun = resolveLatestEvalRun(dataset);

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

    public RetrievalEvaluationOperations.RetrievalEvalReport runRetrievalOfflineEval() {
        return runRetrievalOfflineEval(null);
    }

    public RetrievalEvaluationOperations.RetrievalEvalReport runRetrievalOfflineEval(String dataset) {
        ensureRetrievalEvalEnabled();
        return retrievalEvaluationService.runEvalByDataset(dataset);
    }

    public RetrievalEvaluationOperations.RetrievalEvalReport runRetrievalEvalWithCases(List<RetrievalEvaluationOperations.EvalCase> cases) {
        ensureRetrievalEvalEnabled();
        return retrievalEvaluationService.runCustomEval(cases);
    }

    public RetrievalEvaluationOperations.RetrievalEvalReport runRetrievalEvalWithCases(
            List<RetrievalEvaluationOperations.EvalCase> cases,
            RetrievalEvaluationOperations.EvalRunOptions options
    ) {
        ensureRetrievalEvalEnabled();
        return retrievalEvaluationService.runCustomEval(cases, options);
    }

    public List<RetrievalEvaluationOperations.RetrievalEvalRunSummary> listRecentRetrievalEvalRuns(int limit) {
        ensureRetrievalEvalEnabled();
        return retrievalEvaluationService.listRecentRuns(limit);
    }

    public RetrievalEvaluationOperations.RetrievalEvalReport getRetrievalEvalRunDetail(String runId) {
        ensureRetrievalEvalEnabled();
        return retrievalEvaluationService.getRunDetail(runId);
    }

    public RetrievalEvaluationOperations.RetrievalEvalComparison compareRetrievalEvalRuns(String baselineRunId, String candidateRunId) {
        ensureRetrievalEvalEnabled();
        return retrievalEvaluationService.compareRuns(baselineRunId, candidateRunId);
    }

    public RetrievalEvaluationOperations.RetrievalEvalTrend getRetrievalEvalTrend(int limit) {
        ensureRetrievalEvalEnabled();
        return retrievalEvaluationService.getTrend(limit);
    }

    public List<RetrievalEvaluationOperations.RetrievalEvalFailureCluster> clusterRetrievalEvalFailures(String runId) {
        ensureRetrievalEvalEnabled();
        return retrievalEvaluationService.clusterFailures(runId);
    }

    public List<RetrievalEvaluationOperations.RetrievalEvalParameterTemplate> listRetrievalEvalParameterTemplates() {
        ensureRetrievalEvalEnabled();
        return retrievalEvaluationService.listParameterTemplates();
    }

    public List<RetrievalEvaluationOperations.EvalDatasetDefinition> listRetrievalEvalDatasets() {
        ensureRetrievalEvalEnabled();
        return retrievalEvaluationService.listBuiltInDatasets();
    }

    public List<RetrievalEvaluationOperations.EvalCase> parseRetrievalEvalCsv(String csvText) {
        ensureRetrievalEvalEnabled();
        return retrievalEvaluationService.parseCasesFromCsv(csvText);
    }

    public RagQualityEvaluationOperations.QualityEvalReport runRagQualityEval(String engine) {
        return runRagQualityEval(null, engine);
    }

    public RagQualityEvaluationOperations.QualityEvalReport runRagQualityEval(String dataset, String engine) {
        ensureRagQualityEvalEnabled();
        return ragQualityEvaluationService.runEvalByDataset(dataset, engine);
    }

    public RagQualityEvaluationOperations.QualityEvalReport runRagQualityEvalWithCases(
            List<RagQualityEvaluationOperations.QualityEvalCase> cases,
            RagQualityEvaluationOperations.EvalRunOptions options,
            String engine
    ) {
        ensureRagQualityEvalEnabled();
        return ragQualityEvaluationService.runCustomEval(cases, options, engine);
    }

    public List<RagQualityEvaluationOperations.QualityEvalRunSummary> listRecentRagQualityEvalRuns(int limit) {
        ensureRagQualityEvalEnabled();
        return ragQualityEvaluationService.listRecentRuns(limit);
    }

    public RagQualityEvaluationOperations.QualityEvalReport getRagQualityEvalRunDetail(String runId) {
        ensureRagQualityEvalEnabled();
        return ragQualityEvaluationService.getRunDetail(runId);
    }

    public RagQualityEvaluationOperations.QualityEvalComparison compareRagQualityEvalRuns(String baselineId, String candidateId) {
        ensureRagQualityEvalEnabled();
        return ragQualityEvaluationService.compareRuns(baselineId, candidateId);
    }

    public RagQualityEvaluationOperations.QualityEvalTrend getRagQualityEvalTrend(int limit) {
        ensureRagQualityEvalEnabled();
        return ragQualityEvaluationService.getTrend(limit);
    }

    public Map<String, Object> getRagQualityEvalEngineStatus() {
        ensureRagQualityEvalEnabled();
        return ragQualityEvaluationService.getEngineStatus();
    }

    public List<RagQualityEvaluationOperations.EvalDatasetDefinition> listRagQualityEvalDatasets() {
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

    private RetrievalEvaluationOperations.RetrievalEvalRunSummary resolveLatestEvalRun(String dataset) {
        if (!observabilitySwitchProperties.isRetrievalEvalEnabled()) {
            return null;
        }
        List<RetrievalEvaluationOperations.RetrievalEvalRunSummary> runs =
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









    public Map<String, Object> getDashboard() {
        return observabilityQueryOperations.getDashboard();
    }

    public List<Map<String, Object>> getMetricsHistory(int hours, String metricsParam) {
        return observabilityQueryOperations.getMetricsHistory(hours, metricsParam);
    }

    public Map<String, Object> getMetricsSummary() {
        return observabilityQueryOperations.getMetricsSummary();
    }

    public Map<String, Object> triggerSnapshot() {
        return observabilityQueryOperations.triggerSnapshot();
    }
}






