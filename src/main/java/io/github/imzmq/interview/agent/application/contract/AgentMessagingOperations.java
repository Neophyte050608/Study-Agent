package io.github.imzmq.interview.agent.application.contract;

import io.github.imzmq.interview.agent.application.contract.command.AgentMessageCommand;
import io.github.imzmq.interview.agent.application.contract.message.AgentMessage;

import java.util.function.Consumer;

/** Public surface for publishing and consuming asynchronous Agent messages. */
public interface AgentMessagingOperations {

    void publish(AgentMessageCommand command);

    void subscribe(String receiver, Consumer<AgentMessage> handler);
}
