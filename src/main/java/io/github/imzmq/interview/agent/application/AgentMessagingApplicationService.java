package io.github.imzmq.interview.agent.application;

import io.github.imzmq.interview.agent.a2a.A2ABus;
import io.github.imzmq.interview.agent.a2a.A2AIntent;
import io.github.imzmq.interview.agent.a2a.A2AMessage;
import io.github.imzmq.interview.agent.a2a.A2AMetadata;
import io.github.imzmq.interview.agent.a2a.A2AStatus;
import io.github.imzmq.interview.agent.a2a.A2ATrace;
import io.github.imzmq.interview.agent.application.contract.AgentMessagingOperations;
import io.github.imzmq.interview.agent.application.contract.command.AgentMessageCommand;
import io.github.imzmq.interview.agent.application.contract.message.AgentMessage;
import io.github.imzmq.interview.agent.application.contract.message.AgentMessageIntent;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

@Service
public class AgentMessagingApplicationService implements AgentMessagingOperations {

    private final A2ABus a2aBus;

    public AgentMessagingApplicationService(A2ABus a2aBus) {
        this.a2aBus = a2aBus;
    }

    @Override
    public void publish(AgentMessageCommand command) {
        a2aBus.publish(toInternalMessage(command));
    }

    @Override
    public void subscribe(String receiver, Consumer<AgentMessage> handler) {
        a2aBus.subscribe(receiver, message -> handler.accept(new AgentMessage(
                AgentMessageIntent.valueOf(message.intent().name()),
                message.payload()
        )));
    }

    static A2AMessage toInternalMessage(AgentMessageCommand command) {
        return new A2AMessage(
                "1.0",
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                command.sender(),
                command.receiver(),
                "",
                A2AIntent.valueOf(command.intent().name()),
                command.payload(),
                Map.of(),
                A2AStatus.PENDING,
                null,
                new A2AMetadata(command.capability(), command.source(), command.tags()),
                new A2ATrace(command.traceId(), command.parentMessageId()),
                Instant.now()
        );
    }
}
