package com.example.interview.agent;

import com.example.interview.core.RAGTraceContext;
import com.example.interview.modelrouting.ModelRouteType;
import com.example.interview.modelrouting.RoutingChatService;
import com.example.interview.service.PromptManager;
import com.example.interview.service.RAGObservabilityService;
import com.example.interview.service.RAGService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

@Component
public class KnowledgeQaAgent {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeQaAgent.class);

    private final RAGService ragService;
    private final RoutingChatService routingChatService;
    private final PromptManager promptManager;
    private final RAGObservabilityService ragObservabilityService;

    public KnowledgeQaAgent(RAGService ragService,
                            RoutingChatService routingChatService,
                            PromptManager promptManager,
                            RAGObservabilityService ragObservabilityService) {
        this.ragService = ragService;
        this.routingChatService = routingChatService;
        this.promptManager = promptManager;
        this.ragObservabilityService = ragObservabilityService;
    }

    public Map<String, Object> execute(String question, String history) {
        String traceId = RAGTraceContext.getTraceId();
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
            RAGTraceContext.setTraceId(traceId);
        }

        String nodeId = UUID.randomUUID().toString();
        ragObservabilityService.startNode(traceId, nodeId, null, "KNOWLEDGE_QA", "Knowledge Q&A");

        try {
            RAGService.KnowledgePacket packet = ragService.buildKnowledgePacket(question, "");

            Map<String, Object> vars = new HashMap<>();
            vars.put("question", question);
            vars.put("context", packet.context());
            vars.put("evidence", packet.retrievalEvidence());
            vars.put("history", history != null ? history : "");
            String prompt = promptManager.render("knowledge-qa", vars);

            String answer = routingChatService.call(prompt, ModelRouteType.GENERAL, "知识问答");

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("answer", answer);
            result.put("sources", packet.retrievalEvidence());
            result.put("webFallbackUsed", packet.webFallbackUsed());
            result.put("traceId", traceId);

            ragObservabilityService.endNode(traceId, nodeId, question, answer, null);
            log.info("KnowledgeQA 完成, traceId={}, question={}", traceId, question.length() > 50 ? question.substring(0, 50) + "..." : question);
            return result;
        } catch (Exception e) {
            ragObservabilityService.endNode(traceId, nodeId, question, null, e.getMessage());
            log.error("KnowledgeQA 失败, traceId={}", traceId, e);
            throw e;
        }
    }

    /**
     * 流式执行知识问答。RAG 检索同步，LLM 生成通过 tokenConsumer 逐 token 回调。
     */
    public Map<String, Object> executeStream(String question, String history,
                                             Consumer<String> tokenConsumer) {
        String traceId = RAGTraceContext.getTraceId();
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
            RAGTraceContext.setTraceId(traceId);
        }

        String nodeId = UUID.randomUUID().toString();
        ragObservabilityService.startNode(traceId, nodeId, null, "KNOWLEDGE_QA", "Knowledge Q&A Streaming");

        try {
            RAGService.KnowledgePacket packet = ragService.buildKnowledgePacket(question, "");

            Map<String, Object> vars = new HashMap<>();
            vars.put("question", question);
            vars.put("context", packet.context());
            vars.put("evidence", packet.retrievalEvidence());
            vars.put("history", history != null ? history : "");
            String prompt = promptManager.render("knowledge-qa", vars);

            String answer = routingChatService.callStream(prompt, ModelRouteType.GENERAL,
                    "知识问答-流式", tokenConsumer);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("answer", answer);
            result.put("sources", packet.retrievalEvidence());
            result.put("webFallbackUsed", packet.webFallbackUsed());
            result.put("traceId", traceId);

            ragObservabilityService.endNode(traceId, nodeId, question, answer, null);
            log.info("KnowledgeQA流式完成, traceId={}, question={}", traceId,
                    question.length() > 50 ? question.substring(0, 50) + "..." : question);
            return result;
        } catch (Exception e) {
            ragObservabilityService.endNode(traceId, nodeId, question, null, e.getMessage());
            log.error("KnowledgeQA流式失败, traceId={}", traceId, e);
            throw e;
        }
    }
}
