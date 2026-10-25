package io.github.imzmq.interview.knowledge.application.contract.evaluation;

import java.util.List;
import java.util.Map;

/** Public use cases and stable result shapes for Knowledge RAG quality evaluation. */
public interface RagQualityEvaluationOperations {
    QualityEvalReport runDefaultEval(String engine);
    QualityEvalReport runEvalByDataset(String dataset, String engine);
    QualityEvalReport runDefaultEval();
    List<EvalDatasetDefinition> listBuiltInDatasets();
    QualityEvalReport runCustomEval(List<QualityEvalCase> cases);
    QualityEvalReport runCustomEval(List<QualityEvalCase> cases, EvalRunOptions options);
    QualityEvalReport runCustomEval(List<QualityEvalCase> cases, EvalRunOptions options, String engine);
    QualityEvalCaseResult evaluateSingleCase(QualityEvalCase evalCase);
    List<QualityEvalRunSummary> listRecentRuns(int limit);
    QualityEvalReport getRunDetail(String runId);
    QualityEvalComparison compareRuns(String baselineId, String candidateId);
    QualityEvalTrend getTrend(int limit);
    boolean isEvalEnabled();
    String resolveEngine(String requestedEngine);
    Map<String, Object> getEngineStatus();

    record QualityEvalCase(String query, String groundTruthAnswer, List<String> groundTruthKeyConcepts, String tag) {}

    record QualityEvalCaseResult(
            String query,
            String tag,
            String groundTruthAnswer,
            String generatedAnswer,
            String retrievedContext,
            double faithfulness,
            double answerRelevancy,
            double contextPrecision,
            double contextRecall,
            Map<String, String> rationales
    ) {}

    record QualityEvalReport(
            String runId,
            String timestamp,
            String datasetSource,
            String runLabel,
            String experimentTag,
            String engine,
            Map<String, Object> parameterSnapshot,
            String notes,
            int totalCases,
            double avgFaithfulness,
            double avgAnswerRelevancy,
            double avgContextPrecision,
            double avgContextRecall,
            List<QualityEvalCaseResult> results
    ) {}

    record QualityEvalRunSummary(
            String runId,
            String timestamp,
            String datasetSource,
            String runLabel,
            String experimentTag,
            String engine,
            int totalCases,
            double avgFaithfulness,
            double avgAnswerRelevancy,
            double avgContextPrecision,
            double avgContextRecall
    ) {}

    record EvalRunOptions(
            String datasetSource,
            String runLabel,
            String experimentTag,
            Map<String, Object> parameterSnapshot,
            String notes
    ) {}

    record QualityEvalComparison(
            String baselineRunId,
            String candidateRunId,
            EvalMetricDelta faithfulness,
            EvalMetricDelta answerRelevancy,
            EvalMetricDelta contextPrecision,
            EvalMetricDelta contextRecall,
            List<QualityEvalCaseComparison> changedCases
    ) {}

    record EvalMetricDelta(double baseline, double candidate, double delta) {}

    record QualityEvalCaseComparison(
            String query,
            double baselineFaithfulness,
            double candidateFaithfulness,
            double baselineAnswerRelevancy,
            double candidateAnswerRelevancy,
            double baselineContextPrecision,
            double candidateContextPrecision,
            double baselineContextRecall,
            double candidateContextRecall
    ) {}

    record QualityEvalTrend(
            List<QualityEvalRunSummary> runs,
            double avgFaithfulness,
            double avgAnswerRelevancy,
            double avgContextPrecision,
            double avgContextRecall,
            double bestFaithfulness
    ) {}

    record EvalDatasetDefinition(String datasetId, String fileName, String title, String description) {}
}
