package io.github.imzmq.interview.knowledge.application.context;

import io.github.imzmq.interview.agent.application.contract.context.AgentContextMode;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextQuery;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSlot;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSlotFilter;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSlotKind;
import io.github.imzmq.interview.agent.application.contract.context.ContextItem;

import io.github.imzmq.interview.knowledge.domain.TurnAnalysis;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogSignalContextSourceTest {

    @Test
    void fetchBuildsDialogSignalWithoutExternalDependencies() {
        DialogSignalContextSource source = new DialogSignalContextSource();

        List<ContextItem> items = source.fetch(
                new AgentContextSlot(AgentContextSlotKind.DIALOG_SIGNAL, false, AgentContextSlotFilter.none()),
                AgentContextQuery.create(
                        AgentContextMode.KNOWLEDGE_QA,
                        "query",
                        Map.of(
                                KnowledgeQaContextAttributes.ANALYSIS, TurnAnalysis.firstTurn("JVM"),
                                KnowledgeQaContextAttributes.CONTEXT_POLICY, "SWITCH"
                        )
                )
        );

        assertEquals(1, items.size());
        assertTrue(items.get(0).text().contains("新话题"));
        assertTrue(items.get(0).text().contains("JVM"));
    }

    @Test
    void fetchReturnsEmptyWhenAnalysisMissing() {
        DialogSignalContextSource source = new DialogSignalContextSource();

        List<ContextItem> items = source.fetch(
                new AgentContextSlot(AgentContextSlotKind.DIALOG_SIGNAL, false, AgentContextSlotFilter.none()),
                AgentContextQuery.create(AgentContextMode.KNOWLEDGE_QA, "query", Map.of())
        );

        assertTrue(items.isEmpty());
    }
}
