package com.example.interview.service.interview;

import com.example.interview.config.ObservabilitySwitchProperties;
import com.example.interview.service.RAGObservabilityService;
import com.example.interview.service.RAGQualityEvaluationService;
import com.example.interview.service.RetrievalEvaluationService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Locale;

@Service
public class ObservabilityApplicationService {
    private static final int TRACE_FILTER_SCAN_LIMIT = 1_000;

    private final RAGObservabilityService ragObservabilityService;
    private final RetrievalEvaluationService retrievalEvaluationService;
    private final RAGQualityEvaluationService ragQualityEvaluationService;
    private final ObservabilitySwitchProperties observabilitySwitchProperties;

    public ObservabilityApplicationService(RAGObservabilityService ragObservabilityService,
                                           RetrievalEvaluationService retrievalEvaluationService,
                                           RAGQualityEvaluationService ragQualityEvaluationService,
                                           ObservabilitySwitchProperties observabilitySwitchProperties) {
        this.ragObservabilityService = ragObservabilityService;
        this.retrievalEvaluationService = retrievalEvaluationService;
        this.ragQualityEvaluationService = ragQualityEvaluationService;
        this.observabilitySwitchProperties = observabilitySwitchProperties;
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
}
