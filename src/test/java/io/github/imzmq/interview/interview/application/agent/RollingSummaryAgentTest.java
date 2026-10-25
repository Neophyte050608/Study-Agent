package io.github.imzmq.interview.interview.application.agent;

import io.github.imzmq.interview.agent.application.contract.AgentMessagingOperations;
import io.github.imzmq.interview.agent.application.contract.command.AgentMessageCommand;
import io.github.imzmq.interview.agent.application.contract.message.AgentMessage;
import io.github.imzmq.interview.agent.application.contract.message.AgentMessageIntent;
import io.github.imzmq.interview.interview.domain.InterviewSession;
import io.github.imzmq.interview.interview.infrastructure.persistence.InterviewSessionMapper;
import io.github.imzmq.interview.interview.infrastructure.persistence.SessionRepository;
import io.github.imzmq.interview.modelrouting.core.ModelRouteType;
import io.github.imzmq.interview.modelrouting.core.RoutingChatService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class RollingSummaryAgentTest {

    @Test
    void shouldUpdateOnlyRollingSummaryWithoutSavingWholeSession() throws Exception {
        InterviewSession session = new InterviewSession();
        session.setId("s-1");
        session.setRollingSummary("old-summary");

        AtomicBoolean wholeSessionSaved = new AtomicBoolean();
        SessionRepository sessionRepository = new SessionRepository() {
            @Override
            public InterviewSession save(InterviewSession savedSession) {
                wholeSessionSaved.set(true);
                return savedSession;
            }

            @Override
            public Optional<InterviewSession> findById(String sessionId) {
                return "s-1".equals(sessionId) ? Optional.of(session) : Optional.empty();
            }
        };

        AtomicBoolean rollingSummaryUpdated = new AtomicBoolean();
        InterviewSessionMapper interviewSessionMapper = (InterviewSessionMapper) Proxy.newProxyInstance(
                InterviewSessionMapper.class.getClassLoader(),
                new Class<?>[]{InterviewSessionMapper.class},
                (proxy, method, args) -> {
                    if ("updateRollingSummary".equals(method.getName())) {
                        assertThat(args).containsExactly("s-1", "new-summary");
                        rollingSummaryUpdated.set(true);
                        return 1;
                    }
                    return defaultValue(method.getReturnType());
                }
        );

        RoutingChatService routingChatService = new RoutingChatService(
                Runnable::run, null, null, null, null, null, null, null
        ) {
            @Override
            public String call(String prompt, ModelRouteType routeType, String stage) {
                return "new-summary";
            }
        };

        RollingSummaryAgent agent = new RollingSummaryAgent(
                noOpMessaging(),
                sessionRepository,
                interviewSessionMapper,
                routingChatService
        );

        AgentMessage message = new AgentMessage(
                AgentMessageIntent.ROLLING_SUMMARY,
                Map.of(
                        "sessionId", "s-1",
                        "targetCount", 5,
                        "recentHistory", List.of(Map.of("q", "q1", "a", "a1"))
                )
        );

        Method handleSummaryTask = RollingSummaryAgent.class.getDeclaredMethod("handleSummaryTask", AgentMessage.class);
        handleSummaryTask.setAccessible(true);
        handleSummaryTask.invoke(agent, message);

        assertThat(rollingSummaryUpdated).isTrue();
        assertThat(wholeSessionSaved).isFalse();
    }

    private static AgentMessagingOperations noOpMessaging() {
        return new AgentMessagingOperations() {
            @Override
            public void publish(AgentMessageCommand command) {
            }

            @Override
            public void subscribe(String receiver, java.util.function.Consumer<AgentMessage> handler) {
            }
        };
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}
