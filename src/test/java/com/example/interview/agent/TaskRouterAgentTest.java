package com.example.interview.agent;

import com.example.interview.agent.a2a.A2ABus;
import com.example.interview.agent.a2a.A2AIntent;
import com.example.interview.agent.task.TaskRequest;
import com.example.interview.agent.task.TaskResponse;
import com.example.interview.agent.task.TaskType;
import com.example.interview.core.InterviewSession;
import com.example.interview.intent.IntentCandidate;
import com.example.interview.intent.IntentRoutingDecision;
import com.example.interview.modelrouting.RoutingChatService;
import com.example.interview.service.*;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.never;
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

    @Mock
    private PromptManager promptManager;

    @Mock
    private RoutingChatService routingChatService;

    @Mock
    private IntentTreeRoutingService intentTreeRoutingService;

    @Mock
    private RAGObservabilityService ragObservabilityService;

    private TaskRouterAgent taskRouterAgent;

    @BeforeEach
    void setUp() {
        taskRouterAgent = new TaskRouterAgent(
                interviewOrchestratorAgent,
                codingPracticeAgent,
                noteMakingAgent,
                learningProfileAgent,
                promptManager,
                intentTreeRoutingService,
                a2aBus,
                routingChatService,
                ragObservabilityService
        );
    }

    @Test
    void shouldRouteInterviewStart() {
        InterviewSession session = new InterviewSession("Java", "", 3);
        when(interviewOrchestratorAgent.startSession("u1", "Java", "", 3, false)).thenReturn(session);
        doNothing().when(a2aBus).publish(any());

        TaskResponse response = taskRouterAgent.dispatch(new TaskRequest(
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
        InterviewSession session = new InterviewSession("Java", "", 3);
        when(interviewOrchestratorAgent.startSession("u1", "Java", "", 3, false)).thenReturn(session);
        doNothing().when(a2aBus).publish(any());

        taskRouterAgent.dispatch(new TaskRequest(
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
        when(learningProfileAgent.normalizeUserId(any())).thenReturn("local-user");
        when(codingPracticeAgent.execute(any())).thenReturn(Map.of("status", "started"));
        doNothing().when(a2aBus).publish(any());

        taskRouterAgent.dispatch(new TaskRequest(
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
        doNothing().when(a2aBus).publish(any());
        when(noteMakingAgent.execute(any())).thenReturn(Map.of("status", "not_implemented"));

        TaskResponse response = taskRouterAgent.dispatch(new TaskRequest(
                TaskType.LEARNING_PLAN,
                Map.of(),
                Map.of()
        ));

        assertTrue(response.success());
        assertTrue(response.data() instanceof Map<?, ?>);
    }

    @Test
    void shouldRouteCodingPracticeToCodingAgent() {
        when(learningProfileAgent.normalizeUserId(any())).thenReturn("u1");
        doNothing().when(a2aBus).publish(any());
        when(codingPracticeAgent.execute(any())).thenReturn(Map.of("status", "started"));

        TaskResponse response = taskRouterAgent.dispatch(new TaskRequest(
                TaskType.CODING_PRACTICE,
                Map.of("action", "start"),
                Map.of("userId", "u1")
        ));

        assertTrue(response.success());
        assertTrue(response.data() instanceof Map<?, ?>);
    }

    @Test
    void shouldRouteProfileSnapshotQuery() {
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

        TaskResponse response = taskRouterAgent.dispatch(new TaskRequest(
                TaskType.PROFILE_SNAPSHOT_QUERY,
                Map.of("userId", "u1"),
                Map.of()
        ));

        assertTrue(response.success());
        assertTrue(response.data() instanceof TrainingProfileSnapshot);
    }

    @Test
    void shouldReturnClarificationWhenTreeDecisionRequestsClarify() {
        when(intentTreeRoutingService.enabled()).thenReturn(true);
        when(intentTreeRoutingService.route("我想练习一下", "")).thenReturn(new IntentRoutingDecision(
                false,
                "",
                0.45,
                "ambiguous",
                Map.of(),
                List.of(new IntentCandidate("CODING.PRACTICE.QUESTION", "CODING_PRACTICE", 0.45, "", List.of())),
                true,
                "请问你想刷题还是面试？",
                List.of(Map.of("label", "刷题", "taskType", "CODING_PRACTICE"))
        ));

        TaskResponse response = taskRouterAgent.dispatch(new TaskRequest(null, Map.of("query", "我想练习一下"), Map.of("history", "")));
        assertTrue(response.success());
        assertTrue(response.data() instanceof Map<?, ?>);
        Map<?, ?> data = (Map<?, ?>) response.data();
        assertEquals("请问你想刷题还是面试？", data.get("question"));
        verify(codingPracticeAgent, never()).execute(any());
    }
}
