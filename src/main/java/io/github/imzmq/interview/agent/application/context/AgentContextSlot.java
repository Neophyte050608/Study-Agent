package io.github.imzmq.interview.agent.application.context;

public record AgentContextSlot(
        AgentContextSlotKind kind,
        boolean required,
        AgentContextSlotFilter filter
) {
    public AgentContextSlot {
        if (kind == null) {
            throw new IllegalArgumentException("slot kind is required");
        }
        filter = filter == null ? AgentContextSlotFilter.none() : filter;
    }
}
