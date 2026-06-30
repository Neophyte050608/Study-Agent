package io.github.imzmq.interview.agent.application.context;

import io.github.imzmq.interview.knowledge.domain.KnowledgeContextPacket;
import io.github.imzmq.interview.knowledge.domain.KnowledgeRetrievalMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeContextSourceTest {

    @Test
    void fetchFallsBackToPacketContextWhenSessionMissing() {
        KnowledgeContextSource source = new KnowledgeContextSource(null);
        KnowledgeContextPacket packet = packet("本轮检索内容", "图片说明");

        List<ContextItem> items = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.KNOWLEDGE_QA,
                "query",
                Map.of(KnowledgeQaContextAttributes.PACKET, packet)
        ));

        assertEquals(1, items.size());
        assertTrue(items.get(0).text().contains("本轮检索内容"));
        assertTrue(items.get(0).text().contains("相关图片说明"));
        assertTrue(items.get(0).text().contains("[图N]"));
    }

    @Test
    void fetchReturnsEmptyWhenPacketMissing() {
        KnowledgeContextSource source = new KnowledgeContextSource(null);

        List<ContextItem> items = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.KNOWLEDGE_QA,
                "query",
                Map.of()
        ));

        assertTrue(items.isEmpty());
    }

    private AgentContextSlot slot() {
        return new AgentContextSlot(AgentContextSlotKind.KNOWLEDGE, true, AgentContextSlotFilter.none());
    }

    private KnowledgeContextPacket packet(String context, String imageContext) {
        return new KnowledgeContextPacket(
                KnowledgeRetrievalMode.RAG_ONLY,
                KnowledgeRetrievalMode.RAG_ONLY,
                false,
                true,
                "",
                "query",
                context,
                imageContext,
                "证据",
                List.of(),
                false,
                1,
                List.of("doc-1")
        );
    }
}
