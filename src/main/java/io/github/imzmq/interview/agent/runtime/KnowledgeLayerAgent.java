package io.github.imzmq.interview.agent.runtime;

import io.github.imzmq.interview.knowledge.application.RAGService;
import org.springframework.stereotype.Component;

/**
 * 知识层智能体（KnowledgeLayerAgent）。
 * 
 * 核心职责：作为面试评估的“智囊团”，负责针对当前题目和用户回答，
 * 执行混合检索（RAG）并提取核心证据。它确保了 AI 面试官的评估有据可依，
 * 避免产生事实性幻觉。
 */
@Component
public class KnowledgeLayerAgent {

    private final RAGService ragService;

    public KnowledgeLayerAgent(RAGService ragService) {
        this.ragService = ragService;
    }

    /**
     * 收集支撑评估所需的知识。
     * 
     * @param question 当前题目
     * @param userAnswer 用户回答
     * @return 包含检索查询词、召回文档及引用证据的知识包
     */
    public RAGService.KnowledgePacket gatherKnowledge(String question, String userAnswer) {
        // 委托给 RAGService 执行具体的关键词改写和向量/词法混合检索
        return ragService.buildKnowledgePacket(question, userAnswer);
    }
}


