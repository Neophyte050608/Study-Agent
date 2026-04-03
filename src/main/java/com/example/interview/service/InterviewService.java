package com.example.interview.service;

import com.example.interview.agent.InterviewOrchestratorAgent;
import com.example.interview.agent.TaskRouterAgent;
import com.example.interview.agent.task.TaskRequest;
import com.example.interview.agent.task.TaskResponse;
import com.example.interview.agent.task.TaskType;
import com.example.interview.config.ObservabilitySwitchProperties;
import com.example.interview.core.InterviewSession;
import com.example.interview.core.RAGTraceContext;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 面试业务逻辑门面服务。
 */
@Service
public class InterviewService {

    private final TaskRouterAgent taskRouterAgent;
    private final InterviewOrchestratorAgent orchestratorAgent;
    private final LearningProfileAgent learningProfileAgent;
    private final McpGatewayService mcpGatewayService;
    private final RAGObservabilityService ragObservabilityService;
    private final RetrievalEvaluationService retrievalEvaluationService;
    private final RAGQualityEvaluationService ragQualityEvaluationService;
    private final ObservabilitySwitchProperties observabilitySwitchProperties;

    public InterviewService(
            TaskRouterAgent taskRouterAgent,
            InterviewOrchestratorAgent orchestratorAgent,
            LearningProfileAgent learningProfileAgent,
            McpGatewayService mcpGatewayService,
            RAGObservabilityService ragObservabilityService,
            RetrievalEvaluationService retrievalEvaluationService,
            RAGQualityEvaluationService ragQualityEvaluationService,
            ObservabilitySwitchProperties observabilitySwitchProperties
    ) {
        this.orchestratorAgent = orchestratorAgent;
        this.taskRouterAgent = taskRouterAgent;
        this.learningProfileAgent = learningProfileAgent;
        this.mcpGatewayService = mcpGatewayService;
        this.ragObservabilityService = ragObservabilityService;
        this.retrievalEvaluationService = retrievalEvaluationService;
        this.ragQualityEvaluationService = ragQualityEvaluationService;
        this.observabilitySwitchProperties = observabilitySwitchProperties;
    }

    /**
     * 启动会话：通过 TaskRouterAgent 统一路由到编排 Agent，便于状态消息发布与扩展到异步链路。
     */
    public InterviewSession startSession(String userId, String topic, String resumePath, Integer totalQuestions) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId == null ? "" : userId);
        payload.put("topic", topic == null ? "" : topic);
        payload.put("resumePath", resumePath == null ? "" : resumePath);
        payload.put("totalQuestions", totalQuestions);
        
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("source", "InterviewService.startSession");
        context.put("traceId", RAGTraceContext.getTraceId());
        
        TaskResponse response = taskRouterAgent.dispatch(new TaskRequest(
                TaskType.INTERVIEW_START,
                payload,
                context
        ));
        if (!response.success() || !(response.data() instanceof InterviewSession session)) {
            throw new IllegalStateException(response.message());
        }
        return session;
    }

    /**
     * 提交回答：将外部字段映射为编排 Agent 期望的 payload 结构，并把编排结果裁剪为前端展示模型。
     */
    public AnswerResult submitAnswer(String sessionId, String userAnswer) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("source", "InterviewService.submitAnswer");
        context.put("traceId", RAGTraceContext.getTraceId());
        
        TaskResponse response = taskRouterAgent.dispatch(new TaskRequest(
                TaskType.INTERVIEW_ANSWER,
                Map.of(
                        "sessionId", sessionId == null ? "" : sessionId,
                        "userAnswer", userAnswer == null ? "" : userAnswer
                ),
                context
        ));
        if (!response.success() || !(response.data() instanceof InterviewOrchestratorAgent.AnswerResult result)) {
            throw new IllegalStateException(response.message());
        }
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

    /**
     * 生成最终报告：同样走任务路由，以便记录状态并与画像更新/审计链路保持一致。
     */
    public FinalReport generateFinalReport(String sessionId, String userId) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("source", "InterviewService.generateFinalReport");
        context.put("traceId", RAGTraceContext.getTraceId());
        
        TaskResponse response = taskRouterAgent.dispatch(new TaskRequest(
                TaskType.INTERVIEW_REPORT,
                Map.of(
                        "sessionId", sessionId == null ? "" : sessionId,
                        "userId", userId == null ? "" : userId
                ),
                context
        ));
        if (!response.success() || !(response.data() instanceof InterviewOrchestratorAgent.FinalReport report)) {
            throw new IllegalStateException(response.message());
        }
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

    public LearningProfileAgent.TopicCapabilityCurve getTopicCapabilityCurve(String userId, String topic) {
        return learningProfileAgent.getTopicCapabilityCurve(userId, topic);
    }

    public Map<String, Object> getProfileOverview(String userId) {
        return learningProfileAgent.overview(userId);
    }

    public String getProfileRecommendation(String userId, String mode) {
        return learningProfileAgent.recommend(userId, mode);
    }

    public java.util.List<Map<String, Object>> getProfileEvents(String userId, int limit) {
        return learningProfileAgent.listEvents(userId, limit);
    }

    public java.util.List<String> discoverMcpCapabilities(String userId) {
        return mcpGatewayService.discoverCapabilities(userId);
    }

    public java.util.List<String> discoverMcpCapabilities(String userId, String traceId) {
        return mcpGatewayService.discoverCapabilities(userId, traceId);
    }

    public Map<String, Object> invokeMcpCapability(String userId, String capability, Map<String, Object> params, Map<String, Object> context) {
        return mcpGatewayService.invoke(userId, capability, params, context);
    }

    public java.util.List<RAGObservabilityService.RAGTrace> getRecentRagTraces(int limit) {
        if (!observabilitySwitchProperties.isRagTraceEnabled()) {
            return java.util.List.of();
        }
        return ragObservabilityService.listRecent(limit);
    }

    /**
     * 获取运行中的 RAG Trace 列表（活动态）。
     *
     * @param limit 返回条数上限
     * @return 活动态 Trace 列表
     */
    public java.util.List<RAGObservabilityService.RAGTrace> getActiveRagTraces(int limit) {
        if (!observabilitySwitchProperties.isRagTraceEnabled()) {
            return java.util.List.of();
        }
        return ragObservabilityService.listActive(limit);
    }

    /**
     * 获取单条 RAG Trace 详情。
     *
     * @param traceId Trace ID
     * @return Trace 详情；不存在时返回 null
     */
    public RAGObservabilityService.RAGTrace getRagTraceDetail(String traceId) {
        if (!observabilitySwitchProperties.isRagTraceEnabled()) {
            return null;
        }
        return ragObservabilityService.getTraceDetail(traceId);
    }

    public java.util.Map<String, Object> getRagOverview() {
        if (!observabilitySwitchProperties.isRagTraceEnabled()) {
            return java.util.Map.of(
                    "enabled", false,
                    "avgLatencyMs", 0,
                    "avgRetrievedDocs", 0.0,
                    "cacheHitRate", "0.0%"
            );
        }
        return ragObservabilityService.getOverview();
    }

    public RetrievalEvaluationService.RetrievalEvalReport runRetrievalOfflineEval() {
        ensureRetrievalEvalEnabled();
        return retrievalEvaluationService.runDefaultEval();
    }

    public RetrievalEvaluationService.RetrievalEvalReport runRetrievalEvalWithCases(java.util.List<RetrievalEvaluationService.EvalCase> cases) {
        ensureRetrievalEvalEnabled();
        return retrievalEvaluationService.runCustomEval(cases);
    }

    /**
     * 使用带实验元数据的评测配置运行检索评测。
     *
     * @param cases 评测用例
     * @param options 评测运行配置
     * @return 评测报告
     */
    public RetrievalEvaluationService.RetrievalEvalReport runRetrievalEvalWithCases(
            java.util.List<RetrievalEvaluationService.EvalCase> cases,
            RetrievalEvaluationService.EvalRunOptions options
    ) {
        ensureRetrievalEvalEnabled();
        return retrievalEvaluationService.runCustomEval(cases, options);
    }

    /**
     * 查询最近的检索评测运行历史。
     *
     * @param limit 返回条数上限
     * @return 评测运行摘要列表
     */
    public java.util.List<RetrievalEvaluationService.RetrievalEvalRunSummary> listRecentRetrievalEvalRuns(int limit) {
        ensureRetrievalEvalEnabled();
        return retrievalEvaluationService.listRecentRuns(limit);
    }

    /**
     * 查询单次检索评测详情。
     *
     * @param runId 运行 ID
     * @return 评测详情；不存在时返回 null
     */
    public RetrievalEvaluationService.RetrievalEvalReport getRetrievalEvalRunDetail(String runId) {
        ensureRetrievalEvalEnabled();
        return retrievalEvaluationService.getRunDetail(runId);
    }

    /**
     * 对比两次检索评测运行结果。
     *
     * @param baselineRunId 基线运行 ID
     * @param candidateRunId 候选运行 ID
     * @return 对比结果；任一运行不存在时返回 null
     */
    public RetrievalEvaluationService.RetrievalEvalComparison compareRetrievalEvalRuns(String baselineRunId, String candidateRunId) {
        ensureRetrievalEvalEnabled();
        return retrievalEvaluationService.compareRuns(baselineRunId, candidateRunId);
    }

    /**
     * 查询检索评测趋势摘要。
     *
     * @param limit 历史窗口大小
     * @return 趋势结果
     */
    public RetrievalEvaluationService.RetrievalEvalTrend getRetrievalEvalTrend(int limit) {
        ensureRetrievalEvalEnabled();
        return retrievalEvaluationService.getTrend(limit);
    }

    /**
     * 查询指定运行的失败样本聚类结果。
     *
     * @param runId 运行 ID
     * @return 聚类结果；运行不存在时返回 null
     */
    public java.util.List<RetrievalEvaluationService.RetrievalEvalFailureCluster> clusterRetrievalEvalFailures(String runId) {
        ensureRetrievalEvalEnabled();
        return retrievalEvaluationService.clusterFailures(runId);
    }

    /**
     * 获取检索评测参数模板列表。
     *
     * @return 参数模板列表
     */
    public java.util.List<RetrievalEvaluationService.RetrievalEvalParameterTemplate> listRetrievalEvalParameterTemplates() {
        ensureRetrievalEvalEnabled();
        return retrievalEvaluationService.listParameterTemplates();
    }

    public TaskResponse dispatchTask(TaskType taskType, Map<String, Object> payload, Map<String, Object> context) {
        // 通用任务分发入口：供 /task/dispatch 与其他内部调用复用。
        return taskRouterAgent.dispatch(new TaskRequest(
                taskType,
                payload == null ? Map.of() : payload,
                context == null ? Map.of() : context
        ));
    }

    public java.util.List<RetrievalEvaluationService.EvalCase> parseRetrievalEvalCsv(String csvText) {
        ensureRetrievalEvalEnabled();
        return retrievalEvaluationService.parseCasesFromCsv(csvText);
    }

    public RAGQualityEvaluationService.QualityEvalReport runRAGQualityEval() {
        return runRAGQualityEval(null);
    }

    public RAGQualityEvaluationService.QualityEvalReport runRAGQualityEval(String engine) {
        ensureRagQualityEvalEnabled();
        return ragQualityEvaluationService.runDefaultEval(engine);
    }

    public RAGQualityEvaluationService.QualityEvalReport runRAGQualityEvalWithCases(
            List<RAGQualityEvaluationService.QualityEvalCase> cases,
            RAGQualityEvaluationService.EvalRunOptions options
    ) {
        return runRAGQualityEvalWithCases(cases, options, null);
    }

    public RAGQualityEvaluationService.QualityEvalReport runRAGQualityEvalWithCases(
            List<RAGQualityEvaluationService.QualityEvalCase> cases,
            RAGQualityEvaluationService.EvalRunOptions options,
            String engine
    ) {
        ensureRagQualityEvalEnabled();
        return ragQualityEvaluationService.runCustomEval(cases, options, engine);
    }

    public List<RAGQualityEvaluationService.QualityEvalRunSummary> listRecentRAGQualityEvalRuns(int limit) {
        ensureRagQualityEvalEnabled();
        return ragQualityEvaluationService.listRecentRuns(limit);
    }

    public RAGQualityEvaluationService.QualityEvalReport getRAGQualityEvalRunDetail(String runId) {
        ensureRagQualityEvalEnabled();
        return ragQualityEvaluationService.getRunDetail(runId);
    }

    public RAGQualityEvaluationService.QualityEvalComparison compareRAGQualityEvalRuns(String baselineId, String candidateId) {
        ensureRagQualityEvalEnabled();
        return ragQualityEvaluationService.compareRuns(baselineId, candidateId);
    }

    public RAGQualityEvaluationService.QualityEvalTrend getRAGQualityEvalTrend(int limit) {
        ensureRagQualityEvalEnabled();
        return ragQualityEvaluationService.getTrend(limit);
    }

    public Map<String, Object> getRAGQualityEvalEngineStatus() {
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

    public java.util.Map<String, Object> getObservabilitySwitches() {
        return java.util.Map.of(
                "ragTraceEnabled", observabilitySwitchProperties.isRagTraceEnabled(),
                "retrievalEvalEnabled", observabilitySwitchProperties.isRetrievalEvalEnabled(),
                "ragQualityEvalEnabled", observabilitySwitchProperties.isRagQualityEvalEnabled()
        );
    }

    public java.util.Map<String, Object> updateObservabilitySwitches(Boolean ragTraceEnabled, Boolean retrievalEvalEnabled, Boolean ragQualityEvalEnabled) {
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
