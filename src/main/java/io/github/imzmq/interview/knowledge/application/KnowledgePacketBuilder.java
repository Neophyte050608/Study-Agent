package io.github.imzmq.interview.knowledge.application;

import org.springframework.stereotype.Service;

@Service
public class KnowledgePacketBuilder {

    public RAGService.KnowledgePacket build(String question,
                                            String userAnswer,
                                            boolean allowWebFallback,
                                            BuildDelegate delegate) {
        return delegate.buildKnowledgePacketInternal(question, userAnswer, allowWebFallback);
    }

    @FunctionalInterface
    public interface BuildDelegate {
        RAGService.KnowledgePacket buildKnowledgePacketInternal(String question,
                                                                String userAnswer,
                                                                boolean allowWebFallback);
    }
}
