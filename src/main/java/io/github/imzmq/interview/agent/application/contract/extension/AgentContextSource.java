package io.github.imzmq.interview.agent.application.contract.extension;

import io.github.imzmq.interview.agent.application.contract.context.AgentContextQuery;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSlot;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSlotKind;
import io.github.imzmq.interview.agent.application.contract.context.ContextItem;

import java.util.List;

public interface AgentContextSource {
    String id();

    boolean supports(AgentContextSlotKind kind);

    List<ContextItem> fetch(AgentContextSlot slot, AgentContextQuery query);
}
