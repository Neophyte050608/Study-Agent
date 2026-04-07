package com.example.interview.service;

import org.springframework.stereotype.Service;

/**
 * RAG 适配层。
 *
 * <p>将现有 {@link RAGService} 输出适配为统一的 {@link KnowledgeContextPacket}，
 * 便于后续在不破坏上层 Agent 的前提下接入本地知识图和融合模式。</p>
 */
@Service
public class RagKnowledgeService {

    private final RAGService ragService;

    public RagKnowledgeService(RAGService ragService) {
        this.ragService = ragService;
    }

    public KnowledgeContextPacket retrieve(String question,
                                           String userAnswer,
                                           KnowledgeRetrievalMode requestedMode,
                                           KnowledgeRetrievalMode resolvedMode,
                                           String fallbackReason) {
        RAGService.KnowledgePacket packet = ragService.buildKnowledgePacket(question, userAnswer);
        return new KnowledgeContextPacket(
                requestedMode,
                resolvedMode,
                false,
                true,
                fallbackReason,
                packet.retrievalQuery(),
                packet.context(),
                packet.imageContext(),
                packet.retrievalEvidence(),
                packet.retrievedImages(),
                packet.webFallbackUsed()
        );
    }
}
