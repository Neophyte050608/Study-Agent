package io.github.imzmq.interview.agent.application.contract.context;

import java.util.List;

/**
 * Context slot schema contributed by the owner of an agent context mode.
 */
public record AgentContextSchema(
        AgentContextMode mode,
        List<AgentContextSlot> slots
) {
    public AgentContextSchema {
        mode = mode == null ? AgentContextMode.KNOWLEDGE_QA : mode;
        slots = slots == null ? List.of() : List.copyOf(slots);
    }
}
