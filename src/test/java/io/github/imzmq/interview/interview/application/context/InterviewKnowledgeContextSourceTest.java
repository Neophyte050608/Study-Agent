package io.github.imzmq.interview.interview.application.context;

import io.github.imzmq.interview.agent.application.contract.context.AgentContextMode;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextQuery;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSlot;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSlotFilter;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSlotKind;
import io.github.imzmq.interview.agent.application.contract.context.ContextItem;
import io.github.imzmq.interview.knowledge.application.contract.result.KnowledgePacket;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterviewKnowledgeContextSourceTest {

    @Test
    void fetchReturnsEmptyWhenPacketMissing() {
        InterviewKnowledgeContextSource source = new InterviewKnowledgeContextSource();

        List<ContextItem> items = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.INTERVIEW,
                "query",
                Map.of()
        ));

        assertTrue(items.isEmpty());
    }

    @Test
    void fetchRendersPacketContextImageAndEvidenceSummary() {
        InterviewKnowledgeContextSource source = new InterviewKnowledgeContextSource();
        KnowledgePacket packet = new KnowledgePacket(
                "full query",
                1,
                "RAG 文本上下文",
                "图片上下文",
                "[1] 文档证据内容",
                false
        );

        List<ContextItem> items = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.INTERVIEW,
                "query",
                Map.of(InterviewContextAttributes.KNOWLEDGE_PACKET, packet)
        ));

        assertEquals(1, items.size());
        String text = items.get(0).text();
        assertTrue(text.contains("RAG 文本上下文"));
        assertTrue(text.contains("图片上下文"));
        assertTrue(text.contains("证据目录摘要"));
        assertTrue(text.contains("[1] 文档证据内容"));
    }

    private AgentContextSlot slot() {
        return new AgentContextSlot(AgentContextSlotKind.KNOWLEDGE, true, AgentContextSlotFilter.none());
    }
}
