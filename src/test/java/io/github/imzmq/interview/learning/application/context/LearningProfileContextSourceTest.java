package io.github.imzmq.interview.learning.application.context;

import io.github.imzmq.interview.agent.application.contract.context.AgentContextMode;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextQuery;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSlot;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSlotFilter;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSlotKind;
import io.github.imzmq.interview.agent.application.contract.context.ContextItem;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LearningProfileContextSourceTest {

    @Test
    void fetchReturnsEmptyWhenNoUserId() {
        LearningProfileContextSource source = new LearningProfileContextSource(null);

        List<ContextItem> items = source.fetch(
                new AgentContextSlot(AgentContextSlotKind.PROFILE, false, AgentContextSlotFilter.none()),
                AgentContextQuery.create(AgentContextMode.KNOWLEDGE_QA, "query", Map.of())
        );

        assertTrue(items.isEmpty());
    }

    @Test
    void supportsOnlyProfileSlot() {
        LearningProfileContextSource source = new LearningProfileContextSource(null);

        assertTrue(source.supports(AgentContextSlotKind.PROFILE));
    }
}
