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
    private final InterviewLearningProfileService learningProfileService;
    private final LearningProfileAgent learningProfileAgent;
    private final McpGatewayService mcpGatewayService;
    private final RAGObservabilityService ragObservabilityService;
    private final RetrievalEvaluationService retrievalEvaluationService;
    private final ObservabilitySwitchProperties observabilitySwitchProperties;

    public InterviewService(
            TaskRouterAgent taskRouterAgent,
            InterviewOrchestratorAgent orchestratorAgent,
            InterviewLearningProfileService learningProfileService,
            LearningProfileAgent learningProfileAgent,
            McpGatewayService mcpGatewayService,
            RAGObservabilityService ragObservabilityService,
            RetrievalEvaluationService retrievalEvaluationService,
            ObservabilitySwitchProperties observabilitySwitchProperties
    ) {
        this.orchestratorAgent = orchestratorAgent;
        this.taskRouterAgent = taskRouterAgent;
        this.learningProfileService = learningProfileService;
        this.learningProfileAgent = learningProfileAgent;
        this.mcpGatewayService = mcpGatewayService;
        this.ragObservabilityService = ragObservabilityService;
        this.retrievalEvaluationService = retrievalEvaluationService;
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

    public InterviewLearningProfileService.TopicCapabilityCurve getTopicCapabilityCurve(String userId, String topic) {
        return learningProfileService.getTopicCapabilityCurve(userId, topic);
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

    public boolean isRagTraceEnabled() {
        return observabilitySwitchProperties.isRagTraceEnabled();
    }

    public boolean isRetrievalEvalEnabled() {
        return observabilitySwitchProperties.isRetrievalEvalEnabled();
    }

    public java.util.Map<String, Object> getObservabilitySwitches() {
        return java.util.Map.of(
                "ragTraceEnabled", observabilitySwitchProperties.isRagTraceEnabled(),
                "retrievalEvalEnabled", observabilitySwitchProperties.isRetrievalEvalEnabled()
        );
    }

    public java.util.Map<String, Object> updateObservabilitySwitches(Boolean ragTraceEnabled, Boolean retrievalEvalEnabled) {
        if (ragTraceEnabled != null) {
            observabilitySwitchProperties.setRagTraceEnabled(ragTraceEnabled);
        }
        if (retrievalEvalEnabled != null) {
            observabilitySwitchProperties.setRetrievalEvalEnabled(retrievalEvalEnabled);
        }
        return getObservabilitySwitches();
    }

    private void ensureRetrievalEvalEnabled() {
        if (!observabilitySwitchProperties.isRetrievalEvalEnabled()) {
            throw new IllegalStateException("召回率评测已关闭，请设置 app.observability.retrieval-eval-enabled=true 后重试");
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
