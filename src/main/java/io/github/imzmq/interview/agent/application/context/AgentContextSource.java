package io.github.imzmq.interview.agent.application.context;

import java.util.List;

public interface AgentContextSource {
    String id();

    boolean supports(AgentContextSlotKind kind);

    List<ContextItem> fetch(AgentContextSlot slot, AgentContextQuery query);
}
