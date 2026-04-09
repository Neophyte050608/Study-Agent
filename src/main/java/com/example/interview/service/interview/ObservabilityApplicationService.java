package com.example.interview.service.interview;

import com.example.interview.config.ObservabilitySwitchProperties;
import com.example.interview.service.RAGObservabilityService;
import com.example.interview.service.RAGQualityEvaluationService;
import com.example.interview.service.RetrievalEvaluationService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class ObservabilityApplicationService {

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

    public List<RAGObservabilityService.RAGTrace> getRecentRagTraces(int limit) {
        if (!observabilitySwitchProperties.isRagTraceEnabled()) {
            return List.of();
        }
        return ragObservabilityService.listRecent(limit);
    }

    public List<RAGObservabilityService.RAGTrace> getActiveRagTraces(int limit) {
        if (!observabilitySwitchProperties.isRagTraceEnabled()) {
            return List.of();
        }
        return ragObservabilityService.listActive(limit);
    }

    public RAGObservabilityService.RAGTrace getRagTraceDetail(String traceId) {
        if (!observabilitySwitchProperties.isRagTraceEnabled()) {
            return null;
        }
        return ragObservabilityService.getTraceDetail(traceId);
    }

    public Map<String, Object> getRagOverview() {
        if (!observabilitySwitchProperties.isRagTraceEnabled()) {
            return Map.of("enabled", false, "avgLatencyMs", 0, "avgRetrievedDocs", 0.0, "cacheHitRate", "0.0%");
        }
        return ragObservabilityService.getOverview();
    }

    public RetrievalEvaluationService.RetrievalEvalReport runRetrievalOfflineEval() {
        ensureRetrievalEvalEnabled();
        return retrievalEvaluationService.runDefaultEval();
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

    public List<RetrievalEvaluationService.EvalCase> parseRetrievalEvalCsv(String csvText) {
        ensureRetrievalEvalEnabled();
        return retrievalEvaluationService.parseCasesFromCsv(csvText);
    }

    public RAGQualityEvaluationService.QualityEvalReport runRagQualityEval(String engine) {
        ensureRagQualityEvalEnabled();
        return ragQualityEvaluationService.runDefaultEval(engine);
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
}
