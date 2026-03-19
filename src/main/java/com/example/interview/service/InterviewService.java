package com.example.interview.service;

import com.example.interview.agent.InterviewOrchestratorAgent;
import com.example.interview.core.InterviewSession;
import org.springframework.stereotype.Service;

/**
 * 面试业务服务门面。
 * 对外提供统一接口，把请求编排到 Orchestrator，并输出前端所需结构。
 */
@Service
public class InterviewService {

    private final InterviewOrchestratorAgent orchestratorAgent;
    private final InterviewLearningProfileService learningProfileService;
    private final RAGObservabilityService ragObservabilityService;
    private final RetrievalEvaluationService retrievalEvaluationService;

    public InterviewService(InterviewOrchestratorAgent orchestratorAgent, InterviewLearningProfileService learningProfileService, RAGObservabilityService ragObservabilityService, RetrievalEvaluationService retrievalEvaluationService) {
        this.orchestratorAgent = orchestratorAgent;
        this.learningProfileService = learningProfileService;
        this.ragObservabilityService = ragObservabilityService;
        this.retrievalEvaluationService = retrievalEvaluationService;
    }

    public InterviewSession startSession(String userId, String topic, String resumePath, Integer totalQuestions) {
        return orchestratorAgent.startSession(userId, topic, resumePath, totalQuestions);
    }

    public AnswerResult submitAnswer(String sessionId, String userAnswer) {
        // 将编排层结果映射为服务层稳定返回结构，降低上层耦合。
        InterviewOrchestratorAgent.AnswerResult result = orchestratorAgent.submitAnswer(sessionId, userAnswer);
        return new AnswerResult(
                result.score(),
                result.feedback(),
                result.nextQuestion(),
                result.averageScore(),
                result.finished(),
                result.answeredCount(),
                result.totalQuestions(),
                result.difficultyLevel(),
                result.followUpState(),
                result.topicMastery(),
                result.accuracy(),
                result.logic(),
                result.depth(),
                result.boundary(),
                result.deductions(),
                result.citations(),
                result.conflicts()
        );
    }
    
    public InterviewSession getSession(String sessionId) {
        return orchestratorAgent.getSession(sessionId);
    }

    public FinalReport generateFinalReport(String sessionId, String userId) {
        // 报告结构同样做一层映射，便于后续独立演进编排层字段。
        InterviewOrchestratorAgent.FinalReport report = orchestratorAgent.generateFinalReport(sessionId, userId);
        return new FinalReport(
                report.summary(),
                report.incomplete(),
                report.weak(),
                report.wrong(),
                report.obsidianUpdates(),
                report.nextFocus(),
                report.averageScore(),
                report.answeredCount()
        );
    }

    public InterviewLearningProfileService.TopicCapabilityCurve getTopicCapabilityCurve(String userId, String topic) {
        return learningProfileService.getTopicCapabilityCurve(userId, topic);
    }

    public java.util.List<RAGObservabilityService.TraceRecord> getRecentRagTraces(int limit) {
        return ragObservabilityService.listRecent(limit);
    }

    public RetrievalEvaluationService.RetrievalEvalReport runRetrievalOfflineEval() {
        return retrievalEvaluationService.runDefaultEval();
    }

    public RetrievalEvaluationService.RetrievalEvalReport runRetrievalEvalWithCases(java.util.List<RetrievalEvaluationService.EvalCase> cases) {
        return retrievalEvaluationService.runCustomEval(cases);
    }

    public java.util.List<RetrievalEvaluationService.EvalCase> parseRetrievalEvalCsv(String csvText) {
        return retrievalEvaluationService.parseCasesFromCsv(csvText);
    }

    public record AnswerResult(
            int score,
            String feedback,
            String nextQuestion,
            double averageScore,
            boolean finished,
            int answeredCount,
            int totalQuestions,
            String difficultyLevel,
            String followUpState,
            double topicMastery,
            int accuracy,
            int logic,
            int depth,
            int boundary,
            String deductions,
            java.util.List<String> citations,
            java.util.List<String> conflicts
    ) {}

    public record FinalReport(
            String summary,
            String incomplete,
            String weak,
            String wrong,
            String obsidianUpdates,
            String nextFocus,
            double averageScore,
            int answeredCount
    ) {}
}
