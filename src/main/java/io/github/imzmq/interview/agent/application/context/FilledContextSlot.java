package io.github.imzmq.interview.agent.application.context;

import java.util.List;

public record FilledContextSlot(
        AgentContextSlotKind kind,
        List<ContextItem> items,
        boolean skipped,
        String reason
) {
    public FilledContextSlot {
        if (kind == null) {
            throw new IllegalArgumentException("slot kind is required");
        }
        items = items == null ? List.of() : List.copyOf(items);
        reason = reason == null ? "" : reason;
    }
}
