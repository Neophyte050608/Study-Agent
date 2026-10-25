package io.github.imzmq.interview.knowledge.application.contract;

import io.github.imzmq.interview.knowledge.application.contract.result.KnowledgePacket;

/** Public Knowledge use case for building retrieval context used by other modules. */
public interface KnowledgeRetrievalOperations {
    KnowledgePacket buildKnowledgePacket(String question, String userAnswer);
}
