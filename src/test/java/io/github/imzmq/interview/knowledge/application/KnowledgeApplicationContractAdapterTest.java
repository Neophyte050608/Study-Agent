package io.github.imzmq.interview.knowledge.application;

import io.github.imzmq.interview.knowledge.application.contract.result.KnowledgePacket;
import io.github.imzmq.interview.learning.application.contract.extension.LearningPlanGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeApplicationContractAdapterTest {

    @Test
    void shouldExposeFrameworkNeutralKnowledgePacket() {
        RAGService ragService = mock(RAGService.class);
        when(ragService.buildKnowledgePacket("question", "answer"))
                .thenReturn(new RAGService.KnowledgePacket(
                        "rewritten",
                        List.of(new Document("doc-1", "content", java.util.Map.of())),
                        "context",
                        "[1] evidence",
                        true
                ));
        KnowledgeApplicationContractAdapter adapter = new KnowledgeApplicationContractAdapter(ragService);

        KnowledgePacket packet = adapter.buildKnowledgePacket("question", "answer");

        assertEquals("rewritten", packet.retrievalQuery());
        assertEquals(1, packet.retrievedDocumentCount());
        assertEquals("context", packet.context());
        assertEquals("", packet.imageContext());
        assertEquals("[1] evidence", packet.retrievalEvidence());
        assertTrue(packet.webFallbackUsed());
    }

    @Test
    void shouldDelegateLearningPlanGeneration() {
        RAGService ragService = mock(RAGService.class);
        when(ragService.generateLearningPlan("Redis", "consistency", "recent"))
                .thenReturn("plan");
        KnowledgeApplicationContractAdapter adapter = new KnowledgeApplicationContractAdapter(ragService);

        LearningPlanGenerator generator = adapter;
        assertEquals("plan", generator.generateLearningPlan("Redis", "consistency", "recent"));
        verify(ragService).generateLearningPlan("Redis", "consistency", "recent");
    }
}
