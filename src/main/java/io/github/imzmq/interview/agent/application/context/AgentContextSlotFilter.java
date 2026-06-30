package io.github.imzmq.interview.agent.application.context;

public record AgentContextSlotFilter(
        int charBudget,
        int topK
) {
    public AgentContextSlotFilter {
        charBudget = Math.max(0, charBudget);
        topK = Math.max(0, topK);
    }

    public static AgentContextSlotFilter none() {
        return new AgentContextSlotFilter(0, 0);
    }
}
