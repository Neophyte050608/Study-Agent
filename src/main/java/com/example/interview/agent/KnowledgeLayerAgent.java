package com.example.interview.agent;

import com.example.interview.service.RAGService;
import org.springframework.stereotype.Component;

/**
 * 知识层智能体。
 * 负责组装检索查询、召回结果和证据片段，供评估层直接消费。
 */
@Component
public class KnowledgeLayerAgent {

    private final RAGService ragService;

    public KnowledgeLayerAgent(RAGService ragService) {
        this.ragService = ragService;
    }

    public RAGService.KnowledgePacket gatherKnowledge(String question, String userAnswer) {
        return ragService.buildKnowledgePacket(question, userAnswer);
    }
}
