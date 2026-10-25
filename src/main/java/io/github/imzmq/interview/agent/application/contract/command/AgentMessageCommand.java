package io.github.imzmq.interview.agent.application.contract.command;

import io.github.imzmq.interview.agent.application.contract.message.AgentMessageIntent;

import java.util.Map;

/** Command for publishing a business message through the Agent asynchronous channel. */
public record AgentMessageCommand(
        String sender,
        String receiver,
        AgentMessageIntent intent,
        Map<String, Object> payload,
        String capability,
        String source,
        Map<String, Object> tags,
        String traceId,
        String parentMessageId
) {
    public AgentMessageCommand {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        tags = tags == null ? Map.of() : Map.copyOf(tags);
    }
}
