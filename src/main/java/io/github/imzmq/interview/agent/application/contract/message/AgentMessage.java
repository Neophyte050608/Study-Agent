package io.github.imzmq.interview.agent.application.contract.message;

import java.util.Map;

/** Minimal message delivered to business-owned asynchronous handlers. */
public record AgentMessage(
        AgentMessageIntent intent,
        Map<String, Object> payload
) {
    public AgentMessage {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
