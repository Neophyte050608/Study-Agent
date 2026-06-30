package io.github.imzmq.interview.agent.application.context;

import io.github.imzmq.interview.knowledge.application.RAGService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

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
        RAGService.KnowledgePacket packet = new RAGService.KnowledgePacket(
                "full query",
                List.of(new Document("文档证据内容")),
                List.of(),
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
