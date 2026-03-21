package com.example.interview.agent;

import com.example.interview.agent.a2a.A2ABus;
import com.example.interview.agent.a2a.A2AIntent;
import com.example.interview.agent.task.TaskRequest;
import com.example.interview.agent.task.TaskResponse;
import com.example.interview.agent.task.TaskType;
import com.example.interview.core.InterviewSession;
import com.example.interview.service.LearningProfileAgent;
import com.example.interview.service.TrainingProfileSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskRouterAgentTest {

    @Mock
    private InterviewOrchestratorAgent interviewOrchestratorAgent;

    @Mock
    private A2ABus a2aBus;

    @Mock
    private CodingPracticeAgent codingPracticeAgent;

    @Mock
    private NoteMakingAgent noteMakingAgent;

    @Mock
    private LearningProfileAgent learningProfileAgent;

    @Test
    void shouldRouteInterviewStart() {
        TaskRouterAgent routerAgent = new TaskRouterAgent(interviewOrchestratorAgent, codingPracticeAgent, noteMakingAgent, learningProfileAgent, a2aBus);
        InterviewSession session = new InterviewSession("Java", "", 3);
        when(interviewOrchestratorAgent.startSession("u1", "Java", "", 3)).thenReturn(session);
        doNothing().when(a2aBus).publish(any());

        TaskResponse response = routerAgent.dispatch(new TaskRequest(
                TaskType.INTERVIEW_START,
                Map.of(
                        "userId", "u1",
                        "topic", "Java",
                        "resumePath", "",
                        "totalQuestions", 3
                ),
                Map.of()
        ));

        assertTrue(response.success());
        assertEquals(session, response.data());
    }

    @Test
    void shouldKeepTraceAndCorrelationAcrossPublish() {
        TaskRouterAgent routerAgent = new TaskRouterAgent(interviewOrchestratorAgent, codingPracticeAgent, noteMakingAgent, learningProfileAgent, a2aBus);
        InterviewSession session = new InterviewSession("Java", "", 3);
        when(interviewOrchestratorAgent.startSession("u1", "Java", "", 3)).thenReturn(session);
        doNothing().when(a2aBus).publish(any());

        routerAgent.dispatch(new TaskRequest(
                TaskType.INTERVIEW_START,
                Map.of(
                        "userId", "u1",
                        "topic", "Java",
                        "resumePath", "",
                        "totalQuestions", 3
                ),
                Map.of(
                        "traceId", "trace-x",
                        "correlationId", "corr-x",
                        "parentMessageId", "parent-x"
                )
        ));

        ArgumentCaptor<com.example.interview.agent.a2a.A2AMessage> captor =
                ArgumentCaptor.forClass(com.example.interview.agent.a2a.A2AMessage.class);
        verify(a2aBus, times(3)).publish(captor.capture());
        List<com.example.interview.agent.a2a.A2AMessage> messages = captor.getAllValues();
        assertTrue(messages.stream().allMatch(message -> "trace-x".equals(message.trace().traceId())));
        assertTrue(messages.stream().allMatch(message -> "corr-x".equals(message.correlationId())));
        assertTrue(messages.stream().allMatch(message -> "parent-x".equals(message.trace().parentMessageId())));
    }

    @Test
    void shouldPublishReturnResultWhenReplyToProvided() {
        TaskRouterAgent routerAgent = new TaskRouterAgent(interviewOrchestratorAgent, codingPracticeAgent, noteMakingAgent, learningProfileAgent, a2aBus);
        when(learningProfileAgent.normalizeUserId(any())).thenReturn("local-user");
        when(codingPracticeAgent.execute(any())).thenReturn(Map.of("status", "started"));
        doNothing().when(a2aBus).publish(any());

        routerAgent.dispatch(new TaskRequest(
                TaskType.CODING_PRACTICE,
                Map.of("action", "start"),
                Map.of(
                        "traceId", "trace-r",
                        "correlationId", "corr-r",
                        "replyTo", "GatewayAgent"
                )
        ));

        ArgumentCaptor<com.example.interview.agent.a2a.A2AMessage> captor =
                ArgumentCaptor.forClass(com.example.interview.agent.a2a.A2AMessage.class);
        verify(a2aBus, times(4)).publish(captor.capture());
        List<com.example.interview.agent.a2a.A2AMessage> messages = captor.getAllValues();
        assertTrue(messages.stream().anyMatch(message ->
                message.intent() == A2AIntent.RETURN_RESULT && "GatewayAgent".equals(message.receiver())));
    }

    @Test
    void shouldRouteLearningPlanToNoteAgent() {
        TaskRouterAgent routerAgent = new TaskRouterAgent(interviewOrchestratorAgent, codingPracticeAgent, noteMakingAgent, learningProfileAgent, a2aBus);
        doNothing().when(a2aBus).publish(any());
        when(noteMakingAgent.execute(any())).thenReturn(Map.of("status", "not_implemented"));

        TaskResponse response = routerAgent.dispatch(new TaskRequest(
                TaskType.LEARNING_PLAN,
                Map.of(),
                Map.of()
        ));

        assertTrue(response.success());
        assertTrue(response.data() instanceof Map<?, ?>);
    }

    @Test
    void shouldRouteCodingPracticeToCodingAgent() {
        TaskRouterAgent routerAgent = new TaskRouterAgent(interviewOrchestratorAgent, codingPracticeAgent, noteMakingAgent, learningProfileAgent, a2aBus);
        when(learningProfileAgent.normalizeUserId(any())).thenReturn("u1");
        doNothing().when(a2aBus).publish(any());
        when(codingPracticeAgent.execute(any())).thenReturn(Map.of("status", "started"));

        TaskResponse response = routerAgent.dispatch(new TaskRequest(
                TaskType.CODING_PRACTICE,
                Map.of("action", "start"),
                Map.of("userId", "u1")
        ));

        assertTrue(response.success());
        assertTrue(response.data() instanceof Map<?, ?>);
    }

    @Test
    void shouldRouteProfileSnapshotQuery() {
        TaskRouterAgent routerAgent = new TaskRouterAgent(interviewOrchestratorAgent, codingPracticeAgent, noteMakingAgent, learningProfileAgent, a2aBus);
        doNothing().when(a2aBus).publish(any());
        when(learningProfileAgent.normalizeUserId("u1")).thenReturn("u1");
        when(learningProfileAgent.snapshot("u1")).thenReturn(new TrainingProfileSnapshot(
                List.of(),
                List.of(),
                "基本稳定",
                List.of("并发"),
                List.of("并发"),
                2,
                "2026-03-19T12:00:00Z"
        ));

        TaskResponse response = routerAgent.dispatch(new TaskRequest(
                TaskType.PROFILE_SNAPSHOT_QUERY,
                Map.of("userId", "u1"),
                Map.of()
        ));

        assertTrue(response.success());
        assertTrue(response.data() instanceof TrainingProfileSnapshot);
    }
}
