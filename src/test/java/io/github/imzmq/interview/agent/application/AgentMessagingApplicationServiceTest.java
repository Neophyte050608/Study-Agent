package io.github.imzmq.interview.agent.application;

import io.github.imzmq.interview.agent.a2a.A2ABus;
import io.github.imzmq.interview.agent.a2a.A2AMessage;
import io.github.imzmq.interview.agent.application.contract.command.AgentMessageCommand;
import io.github.imzmq.interview.agent.application.contract.message.AgentMessageIntent;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMessagingApplicationServiceTest {

    @Test
    void translates_public_message_command_to_internal_a2a_message() {
        CapturingBus bus = new CapturingBus();
        AgentMessagingApplicationService service = new AgentMessagingApplicationService(bus);

        service.publish(new AgentMessageCommand(
                "InterviewOrchestratorAgent",
                "RollingSummaryAgent",
                AgentMessageIntent.ROLLING_SUMMARY,
                Map.of("sessionId", "s-1"),
                "rolling-summary",
                "InterviewOrchestratorAgent",
                Map.of("batch", 5),
                "trace-1",
                "parent-1"
        ));

        assertThat(bus.published.sender()).isEqualTo("InterviewOrchestratorAgent");
        assertThat(bus.published.receiver()).isEqualTo("RollingSummaryAgent");
        assertThat(bus.published.intent().name()).isEqualTo("ROLLING_SUMMARY");
        assertThat(bus.published.payload()).containsEntry("sessionId", "s-1");
        assertThat(bus.published.metadata().capability()).isEqualTo("rolling-summary");
        assertThat(bus.published.metadata().tags()).containsEntry("batch", 5);
        assertThat(bus.published.trace().traceId()).isEqualTo("trace-1");
        assertThat(bus.published.trace().parentMessageId()).isEqualTo("parent-1");
    }

    @Test
    void translates_internal_subscription_message_to_public_message() {
        CapturingBus bus = new CapturingBus();
        AgentMessagingApplicationService service = new AgentMessagingApplicationService(bus);
        final Object[] received = new Object[1];
        service.subscribe("RollingSummaryAgent", message -> received[0] = message);

        bus.handler.accept(AgentMessagingApplicationService.toInternalMessage(new AgentMessageCommand(
                "ChatContextCompressor",
                "RollingSummaryAgent",
                AgentMessageIntent.ROLLING_SUMMARY,
                Map.of("sessionId", "s-2"),
                "rolling-summary",
                "ChatContextCompressor",
                Map.of(),
                "trace-2",
                null
        )));

        assertThat(received[0]).isEqualTo(new io.github.imzmq.interview.agent.application.contract.message.AgentMessage(
                AgentMessageIntent.ROLLING_SUMMARY,
                Map.of("sessionId", "s-2")
        ));
    }

    private static class CapturingBus implements A2ABus {
        private A2AMessage published;
        private Consumer<A2AMessage> handler;

        @Override
        public void publish(A2AMessage message) {
            this.published = message;
        }

        @Override
        public void subscribe(String receiver, Consumer<A2AMessage> handler) {
            this.handler = handler;
        }

        @Override
        public Optional<A2AMessage> requestReply(A2AMessage message) {
            return Optional.empty();
        }
    }
}
