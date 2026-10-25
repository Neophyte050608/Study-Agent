package io.github.imzmq.interview.knowledge.application.contract.evaluation;

import java.util.List;
import java.util.Map;

/** Public use cases and stable result shapes for Knowledge retrieval evaluation. */
public interface RetrievalEvaluationOperations {
    RetrievalEvalReport runDefaultEval();
    RetrievalEvalReport runEvalByDataset(String dataset);
    List<EvalDatasetDefinition> listBuiltInDatasets();
    RetrievalEvalReport runCustomEval(List<EvalCase> cases);
    RetrievalEvalReport runCustomEval(List<EvalCase> cases, String datasetSource, String runLabel);
    RetrievalEvalReport runCustomEval(List<EvalCase> cases, EvalRunOptions options);
    List<EvalCase> parseCasesFromCsv(String csvText);
    List<RetrievalEvalRunSummary> listRecentRuns(int limit);
    RetrievalEvalReport getRunDetail(String runId);
    RetrievalEvalComparison compareRuns(String baselineRunId, String candidateRunId);
    RetrievalEvalTrend getTrend(int limit);
    List<RetrievalEvalFailureCluster> clusterFailures(String runId);
    List<RetrievalEvalParameterTemplate> listParameterTemplates();
    boolean isEvalEnabled();

    record RetrievalEvalReport(
            String runId,
            String timestamp,
            String datasetSource,
            String runLabel,
            String experimentTag,
            Map<String, Object> parameterSnapshot,
            String notes,
            int totalCases,
            int hitCases,
            double recallAt1,
            double recallAt3,
            double recallAt5,
            double mrr,
            List<EvalResult> results
    ) {}

    record RetrievalEvalRunSummary(
            String runId,
            String timestamp,
            String datasetSource,
            String runLabel,
            String experimentTag,
            Map<String, Object> parameterSnapshot,
            String notes,
            int totalCases,
            int hitCases,
            double recallAt1,
            double recallAt3,
            double recallAt5,
            double mrr
    ) {}

    record EvalResult(
            String query,
            String tag,
            List<String> expectedKeywords,
            boolean hit,
            int rank,
            List<String> retrievedSnippets
    ) {}

    record EvalCase(String query, List<String> expectedKeywords, String tag) {}

    record EvalRunOptions(
            String datasetSource,
            String runLabel,
            String experimentTag,
            Map<String, Object> parameterSnapshot,
            String notes
    ) {}

    record RetrievalEvalComparison(
            String baselineRunId,
            String candidateRunId,
            EvalMetricDelta recallAt1,
            EvalMetricDelta recallAt3,
            EvalMetricDelta recallAt5,
            EvalMetricDelta mrr,
            int improvedCaseCount,
            int regressedCaseCount,
            List<EvalCaseComparison> changedCases
    ) {}

    record EvalMetricDelta(double baseline, double candidate, double delta) {}

    record EvalCaseComparison(
            String query,
            boolean baselineHit,
            boolean candidateHit,
            int baselineRank,
            int candidateRank,
            int rankDelta
    ) {}

    record RetrievalEvalTrend(
            List<RetrievalEvalRunSummary> runs,
            double avgRecallAt5,
            double avgMrr,
            double bestRecallAt5,
            Map<String, Long> experimentDistribution
    ) {}

    record RetrievalEvalFailureCluster(
            String tag,
            int failedCaseCount,
            List<String> sampleQueries,
            List<String> representativeKeywords
    ) {}

    record RetrievalEvalParameterTemplate(
            String templateId,
            String title,
            String description,
            Map<String, Object> parameterSnapshot
    ) {}

    record EvalDatasetDefinition(String datasetId, String fileName, String title, String description) {}
}
