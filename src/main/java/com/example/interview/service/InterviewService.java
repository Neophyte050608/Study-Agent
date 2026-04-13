package com.example.interview.service;

import com.example.interview.agent.InterviewOrchestratorAgent;
import com.example.interview.agent.TaskRouterAgent;
import com.example.interview.agent.task.TaskRequest;
import com.example.interview.agent.task.TaskResponse;
import com.example.interview.agent.task.TaskType;
import com.example.interview.config.ObservabilitySwitchProperties;
import com.example.interview.core.InterviewSession;
import com.example.interview.service.interview.InterviewAnswerView;
import com.example.interview.service.interview.InterviewFinalReportView;
import com.example.interview.service.interview.InterviewSessionApplicationService;
import com.example.interview.service.interview.McpApplicationService;
import com.example.interview.service.interview.ObservabilityApplicationService;
import com.example.interview.service.interview.ProfileApplicationService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 面试业务逻辑门面服务。
 */
@Service
public class InterviewService {

    private final TaskRouterAgent taskRouterAgent;
    private final InterviewSessionApplicationService interviewSessionApplicationService;
    private final ProfileApplicationService profileApplicationService;
    private final McpApplicationService mcpApplicationService;
    private final ObservabilityApplicationService observabilityApplicationService;

    public InterviewService(
            TaskRouterAgent taskRouterAgent,
            InterviewSessionApplicationService interviewSessionApplicationService,
            ProfileApplicationService profileApplicationService,
            McpApplicationService mcpApplicationService,
            ObservabilityApplicationService observabilityApplicationService
    ) {
        this.taskRouterAgent = taskRouterAgent;
        this.interviewSessionApplicationService = interviewSessionApplicationService;
        this.profileApplicationService = profileApplicationService;
        this.mcpApplicationService = mcpApplicationService;
        this.observabilityApplicationService = observabilityApplicationService;
    }

    /**
     * 启动会话：通过 TaskRouterAgent 统一路由到编排 Agent，便于状态消息发布与扩展到异步链路。
     */
    public InterviewSession startSession(String userId, String topic, String resumePath, Integer totalQuestions) {
        return interviewSessionApplicationService.startSession(userId, topic, resumePath, totalQuestions);
    }

    /**
     * 提交回答：将外部字段映射为编排 Agent 期望的 payload 结构，并把编排结果裁剪为前端展示模型。
     */
    public AnswerResult submitAnswer(String sessionId, String userAnswer) {
        InterviewAnswerView result = interviewSessionApplicationService.submitAnswer(sessionId, userAnswer);
        return new AnswerResult(
                result.score(), result.feedback(), result.nextQuestion(), result.averageScore(), result.finished(),
                result.answeredCount(), result.totalQuestions(), result.difficultyLevel(), result.followUpState(),
                result.topicMastery(), result.accuracy(), result.logic(), result.depth(), result.boundary(),
                result.deductions(), result.citations(), result.conflicts()
        );
    }
    
    public InterviewSession getSession(String sessionId) {
        return interviewSessionApplicationService.getSession(sessionId);
    }

    /**
     * 生成最终报告：同样走任务路由，以便记录状态并与画像更新/审计链路保持一致。
     */
    public FinalReport generateFinalReport(String sessionId, String userId) {
        InterviewFinalReportView report = interviewSessionApplicationService.generateFinalReport(sessionId, userId);
        return new FinalReport(
                report.summary(), report.incomplete(), report.weak(), report.wrong(),
                report.obsidianUpdates(), report.nextFocus(), report.averageScore(), report.answeredCount()
        );
    }

    public LearningProfileAgent.TopicCapabilityCurve getTopicCapabilityCurve(String userId, String topic) {
        return profileApplicationService.getTopicCapabilityCurve(userId, topic);
    }

    public Map<String, Object> getProfileOverview(String userId) {
        return profileApplicationService.getProfileOverview(userId);
    }

    public String getProfileRecommendation(String userId, String mode) {
        return profileApplicationService.getProfileRecommendation(userId, mode);
    }

    public java.util.List<Map<String, Object>> getProfileEvents(String userId, int limit) {
        return profileApplicationService.getProfileEvents(userId, limit);
    }

    public java.util.List<String> discoverMcpCapabilities(String userId) {
        return mcpApplicationService.discoverCapabilities(userId);
    }

    public java.util.List<String> discoverMcpCapabilities(String userId, String traceId) {
        return mcpApplicationService.discoverCapabilities(userId, traceId);
    }

    public Map<String, Object> invokeMcpCapability(String userId, String capability, Map<String, Object> params, Map<String, Object> context) {
        return mcpApplicationService.invokeCapability(userId, capability, params, context);
    }

    public java.util.List<RAGObservabilityService.TraceSummary> getRecentRagTraces(int limit) {
        return observabilityApplicationService.getRecentRagTraces(limit);
    }

    public java.util.List<RAGObservabilityService.TraceSummary> getRecentRagTraces(int limit,
                                                                                   String status,
                                                                                   boolean riskyOnly,
                                                                                   boolean fallbackOnly,
                                                                                   boolean emptyRetrievalOnly,
                                                                                   boolean slowOnly,
                                                                                   String query) {
        return getRecentRagTraces(limit, status, riskyOnly, fallbackOnly, emptyRetrievalOnly, slowOnly, query, null, null);
    }

    public java.util.List<RAGObservabilityService.TraceSummary> getRecentRagTraces(int limit,
                                                                                   String status,
                                                                                   boolean riskyOnly,
                                                                                   boolean fallbackOnly,
                                                                                   boolean emptyRetrievalOnly,
                                                                                   boolean slowOnly,
                                                                                   String query,
                                                                                   Instant startedAfter,
                                                                                   Instant endedBefore) {
        return observabilityApplicationService.getRecentRagTraces(
                limit,
                status,
                riskyOnly,
                fallbackOnly,
                emptyRetrievalOnly,
                slowOnly,
                query,
                startedAfter,
                endedBefore
        );
    }

    /**
     * 获取运行中的 RAG Trace 列表（活动态）。
     *
     * @param limit 返回条数上限
     * @return 活动态 Trace 列表
     */
    public java.util.List<RAGObservabilityService.TraceSummary> getActiveRagTraces(int limit) {
        return observabilityApplicationService.getActiveRagTraces(limit);
    }

    /**
     * 获取单条 RAG Trace 详情。
     *
     * @param traceId Trace ID
     * @return Trace 详情；不存在时返回 null
     */
    public RAGObservabilityService.TraceDetailView getRagTraceDetail(String traceId) {
        return observabilityApplicationService.getRagTraceDetail(traceId);
    }

    public java.util.Map<String, Object> getRagOverview() {
        return observabilityApplicationService.getRagOverview();
    }

    public RetrievalEvaluationService.RetrievalEvalReport runRetrievalOfflineEval() {
        return runRetrievalOfflineEval(null);
    }

    public RetrievalEvaluationService.RetrievalEvalReport runRetrievalOfflineEval(String dataset) {
        return observabilityApplicationService.runRetrievalOfflineEval(dataset);
    }

    public RetrievalEvaluationService.RetrievalEvalReport runRetrievalEvalWithCases(java.util.List<RetrievalEvaluationService.EvalCase> cases) {
        return observabilityApplicationService.runRetrievalEvalWithCases(cases);
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
        return observabilityApplicationService.runRetrievalEvalWithCases(cases, options);
    }

    /**
     * 查询最近的检索评测运行历史。
     *
     * @param limit 返回条数上限
     * @return 评测运行摘要列表
     */
    public java.util.List<RetrievalEvaluationService.RetrievalEvalRunSummary> listRecentRetrievalEvalRuns(int limit) {
        return observabilityApplicationService.listRecentRetrievalEvalRuns(limit);
    }

    /**
     * 查询单次检索评测详情。
     *
     * @param runId 运行 ID
     * @return 评测详情；不存在时返回 null
     */
    public RetrievalEvaluationService.RetrievalEvalReport getRetrievalEvalRunDetail(String runId) {
        return observabilityApplicationService.getRetrievalEvalRunDetail(runId);
    }

    /**
     * 对比两次检索评测运行结果。
     *
     * @param baselineRunId 基线运行 ID
     * @param candidateRunId 候选运行 ID
     * @return 对比结果；任一运行不存在时返回 null
     */
    public RetrievalEvaluationService.RetrievalEvalComparison compareRetrievalEvalRuns(String baselineRunId, String candidateRunId) {
        return observabilityApplicationService.compareRetrievalEvalRuns(baselineRunId, candidateRunId);
    }

    /**
     * 查询检索评测趋势摘要。
     *
     * @param limit 历史窗口大小
     * @return 趋势结果
     */
    public RetrievalEvaluationService.RetrievalEvalTrend getRetrievalEvalTrend(int limit) {
        return observabilityApplicationService.getRetrievalEvalTrend(limit);
    }

    /**
     * 查询指定运行的失败样本聚类结果。
     *
     * @param runId 运行 ID
     * @return 聚类结果；运行不存在时返回 null
     */
    public java.util.List<RetrievalEvaluationService.RetrievalEvalFailureCluster> clusterRetrievalEvalFailures(String runId) {
        return observabilityApplicationService.clusterRetrievalEvalFailures(runId);
    }

    /**
     * 获取检索评测参数模板列表。
     *
     * @return 参数模板列表
     */
    public java.util.List<RetrievalEvaluationService.RetrievalEvalParameterTemplate> listRetrievalEvalParameterTemplates() {
        return observabilityApplicationService.listRetrievalEvalParameterTemplates();
    }

    public java.util.List<RetrievalEvaluationService.EvalDatasetDefinition> listRetrievalEvalDatasets() {
        return observabilityApplicationService.listRetrievalEvalDatasets();
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
        return observabilityApplicationService.parseRetrievalEvalCsv(csvText);
    }

    public RAGQualityEvaluationService.QualityEvalReport runRAGQualityEval() {
        return runRAGQualityEval(null, null);
    }

    public RAGQualityEvaluationService.QualityEvalReport runRAGQualityEval(String engine) {
        return runRAGQualityEval(null, engine);
    }

    public RAGQualityEvaluationService.QualityEvalReport runRAGQualityEval(String dataset, String engine) {
        return observabilityApplicationService.runRagQualityEval(dataset, engine);
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
        return observabilityApplicationService.runRagQualityEvalWithCases(cases, options, engine);
    }

    public List<RAGQualityEvaluationService.QualityEvalRunSummary> listRecentRAGQualityEvalRuns(int limit) {
        return observabilityApplicationService.listRecentRagQualityEvalRuns(limit);
    }

    public RAGQualityEvaluationService.QualityEvalReport getRAGQualityEvalRunDetail(String runId) {
        return observabilityApplicationService.getRagQualityEvalRunDetail(runId);
    }

    public RAGQualityEvaluationService.QualityEvalComparison compareRAGQualityEvalRuns(String baselineId, String candidateId) {
        return observabilityApplicationService.compareRagQualityEvalRuns(baselineId, candidateId);
    }

    public RAGQualityEvaluationService.QualityEvalTrend getRAGQualityEvalTrend(int limit) {
        return observabilityApplicationService.getRagQualityEvalTrend(limit);
    }

    public java.util.List<RAGQualityEvaluationService.EvalDatasetDefinition> listRAGQualityEvalDatasets() {
        return observabilityApplicationService.listRagQualityEvalDatasets();
    }

    public Map<String, Object> getRAGQualityEvalEngineStatus() {
        return observabilityApplicationService.getRagQualityEvalEngineStatus();
    }

    public boolean isRagTraceEnabled() {
        return observabilityApplicationService.isRagTraceEnabled();
    }

    public boolean isRetrievalEvalEnabled() {
        return observabilityApplicationService.isRetrievalEvalEnabled();
    }

    public boolean isRagQualityEvalEnabled() {
        return observabilityApplicationService.isRagQualityEvalEnabled();
    }

    public java.util.Map<String, Object> getObservabilitySwitches() {
        return observabilityApplicationService.getObservabilitySwitches();
    }

    public java.util.Map<String, Object> updateObservabilitySwitches(Boolean ragTraceEnabled, Boolean retrievalEvalEnabled, Boolean ragQualityEvalEnabled) {
        return observabilityApplicationService.updateObservabilitySwitches(
                ragTraceEnabled, retrievalEvalEnabled, ragQualityEvalEnabled
        );
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
