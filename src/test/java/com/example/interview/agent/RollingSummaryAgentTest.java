package com.example.interview.agent;

import com.example.interview.agent.a2a.A2AIntent;
import com.example.interview.agent.a2a.A2AMessage;
import com.example.interview.agent.a2a.A2AStatus;
import com.example.interview.agent.a2a.A2ABus;
import com.example.interview.core.InterviewSession;
import com.example.interview.mapper.InterviewSessionMapper;
import com.example.interview.modelrouting.RoutingChatService;
import com.example.interview.session.SessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RollingSummaryAgentTest {

    @Mock
    private SessionRepository sessionRepository;

    @Mock
    private InterviewSessionMapper interviewSessionMapper;

    @Mock
    private RoutingChatService routingChatService;

    @Test
    void shouldUpdateOnlyRollingSummaryWithoutSavingWholeSession() throws Exception {
        RollingSummaryAgent agent = new RollingSummaryAgent(
                new A2ABus() {
                    @Override
                    public void publish(A2AMessage message) {
                    }

                    @Override
                    public void subscribe(String receiver, java.util.function.Consumer<A2AMessage> handler) {
                    }
                },
                sessionRepository,
                interviewSessionMapper,
                routingChatService
        );

        InterviewSession session = new InterviewSession();
        session.setId("s-1");
        session.setRollingSummary("old-summary");
        when(sessionRepository.findById("s-1")).thenReturn(Optional.of(session));
        when(routingChatService.call(anyString(), org.mockito.ArgumentMatchers.any(), anyString())).thenReturn("new-summary");
        when(interviewSessionMapper.updateRollingSummary("s-1", "new-summary")).thenReturn(1);

        A2AMessage message = new A2AMessage(
                "1.0",
                "m-1",
                "c-1",
                "InterviewOrchestratorAgent",
                "RollingSummaryAgent",
                null,
                A2AIntent.ROLLING_SUMMARY,
                Map.of(
                        "sessionId", "s-1",
                        "targetCount", 5,
                        "recentHistory", List.of(Map.of("q", "q1", "a", "a1"))
                ),
                Map.of(),
                A2AStatus.PENDING,
                null,
                null,
                null,
                Instant.now()
        );

        Method handleSummaryTask = RollingSummaryAgent.class.getDeclaredMethod("handleSummaryTask", A2AMessage.class);
        handleSummaryTask.setAccessible(true);
        handleSummaryTask.invoke(agent, message);

        verify(interviewSessionMapper).updateRollingSummary("s-1", "new-summary");
        verify(sessionRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
