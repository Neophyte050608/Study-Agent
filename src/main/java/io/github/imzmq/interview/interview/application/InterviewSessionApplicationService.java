package io.github.imzmq.interview.interview.application;

import io.github.imzmq.interview.agent.runtime.InterviewOrchestratorAgent;
import io.github.imzmq.interview.agent.runtime.TaskRouterAgent;
import io.github.imzmq.interview.agent.task.TaskRequest;
import io.github.imzmq.interview.agent.task.TaskResponse;
import io.github.imzmq.interview.agent.task.TaskType;
import io.github.imzmq.interview.interview.domain.InterviewSession;
import io.github.imzmq.interview.core.trace.RAGTraceContext;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class InterviewSessionApplicationService {

    private final TaskRouterAgent taskRouterAgent;
    private final InterviewOrchestratorAgent orchestratorAgent;

    public InterviewSessionApplicationService(TaskRouterAgent taskRouterAgent,
                                              InterviewOrchestratorAgent orchestratorAgent) {
        this.taskRouterAgent = taskRouterAgent;
        this.orchestratorAgent = orchestratorAgent;
    }

    public InterviewSession startSession(String userId, String topic, String resumePath, Integer totalQuestions) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId == null ? "" : userId);
        payload.put("topic", topic == null ? "" : topic);
        payload.put("resumePath", resumePath == null ? "" : resumePath);
        payload.put("totalQuestions", totalQuestions);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("source", "InterviewService.startSession");
        context.put("traceId", RAGTraceContext.getTraceId());

        TaskResponse response = taskRouterAgent.dispatch(new TaskRequest(TaskType.INTERVIEW_START, payload, context));
        if (!response.success() || !(response.data() instanceof InterviewSession session)) {
            throw new IllegalStateException(response.message());
        }
        return session;
    }

    public InterviewService.AnswerResult submitAnswer(String sessionId, String userAnswer) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("source", "InterviewService.submitAnswer");
        context.put("traceId", RAGTraceContext.getTraceId());

        TaskResponse response = taskRouterAgent.dispatch(new TaskRequest(
                TaskType.INTERVIEW_ANSWER,
                Map.of("sessionId", sessionId == null ? "" : sessionId, "userAnswer", userAnswer == null ? "" : userAnswer),
                context
        ));
        if (!response.success() || !(response.data() instanceof InterviewOrchestratorAgent.AnswerResult result)) {
            throw new IllegalStateException(response.message());
        }
        return new InterviewService.AnswerResult(
                result.score(), result.feedback(), result.nextQuestion(), result.averageScore(), result.finished(),
                result.answeredCount(), result.totalQuestions(), result.difficultyLevel(), result.followUpState(),
                result.topicMastery(), result.accuracy(), result.logic(), result.depth(), result.boundary(),
                result.deductions(), result.citations(), result.conflicts()
        );
    }

    public InterviewSession getSession(String sessionId) {
        return orchestratorAgent.getSession(sessionId);
    }

    public InterviewService.FinalReport generateFinalReport(String sessionId, String userId) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("source", "InterviewService.generateFinalReport");
        context.put("traceId", RAGTraceContext.getTraceId());

        TaskResponse response = taskRouterAgent.dispatch(new TaskRequest(
                TaskType.INTERVIEW_REPORT,
                Map.of("sessionId", sessionId == null ? "" : sessionId, "userId", userId == null ? "" : userId),
                context
        ));
        if (!response.success() || !(response.data() instanceof InterviewOrchestratorAgent.FinalReport report)) {
            throw new IllegalStateException(response.message());
        }
        return new InterviewService.FinalReport(
                report.summary(), report.incomplete(), report.weak(), report.wrong(),
                report.obsidianUpdates(), report.nextFocus(), report.averageScore(), report.answeredCount()
        );
    }
}






