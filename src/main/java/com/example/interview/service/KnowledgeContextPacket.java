package com.example.interview.service;

import java.util.List;

/**
 * 统一知识上下文包。
 *
 * <p>用于把知识检索来源从具体实现中抽离出来，供协调器和上层 Agent 统一消费。</p>
 */
public record KnowledgeContextPacket(
        KnowledgeRetrievalMode retrievalModeRequested,
        KnowledgeRetrievalMode retrievalModeResolved,
        boolean localGraphUsed,
        boolean ragUsed,
        String fallbackReason,
        String retrievalQuery,
        String context,
        String imageContext,
        String retrievalEvidence,
        List<ImageService.ImageResult> retrievedImages,
        boolean webFallbackUsed,
        int retrievedDocCount,
        List<String> retrievedDocumentRefs
) {
    public KnowledgeContextPacket {
        retrievedImages = retrievedImages == null ? List.of() : List.copyOf(retrievedImages);
        retrievedDocumentRefs = retrievedDocumentRefs == null ? List.of() : List.copyOf(retrievedDocumentRefs);
        fallbackReason = fallbackReason == null ? "" : fallbackReason;
        context = context == null ? "" : context;
        imageContext = imageContext == null ? "" : imageContext;
        retrievalEvidence = retrievalEvidence == null ? "" : retrievalEvidence;
        retrievalQuery = retrievalQuery == null ? "" : retrievalQuery;
        retrievedDocCount = Math.max(0, retrievedDocCount);
    }
}
