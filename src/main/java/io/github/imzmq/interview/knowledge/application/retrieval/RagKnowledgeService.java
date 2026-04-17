package io.github.imzmq.interview.knowledge.application.retrieval;
import io.github.imzmq.interview.knowledge.domain.KnowledgeContextPacket;
import io.github.imzmq.interview.knowledge.domain.KnowledgeRetrievalMode;

import io.github.imzmq.interview.knowledge.application.RAGService;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

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
                packet.webFallbackUsed(),
                packet.retrievedDocs() == null ? 0 : packet.retrievedDocs().size(),
                summarizeDocuments(packet.retrievedDocs())
        );
    }

    private List<String> summarizeDocuments(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        return documents.stream()
                .limit(5)
                .map(document -> {
                    String source = String.valueOf(document.getMetadata().getOrDefault("source", ""));
                    String title = String.valueOf(document.getMetadata().getOrDefault("title", ""));
                    String snippet = document.getText() == null ? "" : document.getText().replaceAll("\\s+", " ").trim();
                    if (snippet.length() > 80) {
                        snippet = snippet.substring(0, 80) + "...";
                    }
                    return List.of(source, title, snippet).stream()
                            .filter(item -> item != null && !item.isBlank() && !"null".equalsIgnoreCase(item))
                            .collect(Collectors.joining(" | "));
                })
                .filter(item -> item != null && !item.isBlank())
                .toList();
    }
}








