package com.example.interview.agent;

import com.example.interview.core.RAGTraceContext;
import com.example.interview.modelrouting.ModelRouteType;
import com.example.interview.modelrouting.RoutingChatService;
import com.example.interview.service.KnowledgeContextPacket;
import com.example.interview.service.KnowledgeRetrievalCoordinator;
import com.example.interview.service.KnowledgeRetrievalMode;
import com.example.interview.service.PromptManager;
import com.example.interview.service.RAGObservabilityService;
import com.example.interview.service.knowledge.ConversationTopicTracker;
import com.example.interview.service.knowledge.DynamicKnowledgeContextBuilder;
import com.example.interview.service.knowledge.TurnAnalysis;
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

    private final KnowledgeRetrievalCoordinator knowledgeRetrievalCoordinator;
    private final RoutingChatService routingChatService;
    private final PromptManager promptManager;
    private final RAGObservabilityService ragObservabilityService;
    private final ConversationTopicTracker topicTracker;
    private final DynamicKnowledgeContextBuilder dynamicContextBuilder;

    public KnowledgeQaAgent(KnowledgeRetrievalCoordinator knowledgeRetrievalCoordinator,
                            RoutingChatService routingChatService,
                            PromptManager promptManager,
                            RAGObservabilityService ragObservabilityService,
                            ConversationTopicTracker topicTracker,
                            DynamicKnowledgeContextBuilder dynamicContextBuilder) {
        this.knowledgeRetrievalCoordinator = knowledgeRetrievalCoordinator;
        this.routingChatService = routingChatService;
        this.promptManager = promptManager;
        this.ragObservabilityService = ragObservabilityService;
        this.topicTracker = topicTracker;
        this.dynamicContextBuilder = dynamicContextBuilder;
    }

    public Map<String, Object> execute(String question, String history) {
        return execute(question, history, null);
    }

    public Map<String, Object> execute(String question,
                                       String history,
                                       KnowledgeRetrievalMode retrievalMode) {
        return execute(question, history, retrievalMode, null);
    }

    public Map<String, Object> execute(String question,
                                       String history,
                                       KnowledgeRetrievalMode retrievalMode,
                                       String sessionId) {
        String traceId = RAGTraceContext.getTraceId();
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
            RAGTraceContext.setTraceId(traceId);
        }

        String nodeId = UUID.randomUUID().toString();
        ragObservabilityService.startNode(traceId, nodeId, null, "KNOWLEDGE_QA", "Knowledge Q&A");

        try {
            TurnAnalysis analysis = analyzeTurnSafe(sessionId, question, history);
            KnowledgeContextPacket packet = knowledgeRetrievalCoordinator.retrieve(question, "", retrievalMode);
            String combinedContext;
            String dialogSignal = "";
            if (sessionId != null && !sessionId.isBlank()) {
                combinedContext = dynamicContextBuilder.buildDynamicContext(analysis, packet, sessionId);
                dialogSignal = dynamicContextBuilder.buildDialogSignal(analysis);
            } else {
                combinedContext = buildCombinedContext(packet);
            }

            Map<String, Object> vars = new HashMap<>();
            vars.put("question", question);
            vars.put("context", combinedContext);
            vars.put("imageContext", packet.imageContext());
            vars.put("evidence", packet.retrievalEvidence());
            vars.put("history", history != null ? history : "");
            vars.put("dialogSignal", dialogSignal);
            PromptManager.PromptPair pair = promptManager.renderSplit("knowledge-assistant", "knowledge-qa", vars);
            String answer = routingChatService.call(pair.systemPrompt(), pair.userPrompt(), ModelRouteType.GENERAL, "知识问答");

            if (sessionId != null && !sessionId.isBlank()) {
                topicTracker.generateDigestAsync(sessionId, analysis, packet.context());
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("answer", answer);
            result.put("sources", packet.retrievalEvidence());
            result.put("images", packet.retrievedImages());
            result.put("webFallbackUsed", packet.webFallbackUsed());
            result.put("localGraphUsed", packet.localGraphUsed());
            result.put("ragUsed", packet.ragUsed());
            result.put("fallbackReason", packet.fallbackReason());
            result.put("retrievalModeRequested", packet.retrievalModeRequested());
            result.put("retrievalModeResolved", packet.retrievalModeResolved());
            result.put("traceId", traceId);
            result.put("dialogAct", analysis.dialogAct().name());
            result.put("topicSwitch", analysis.topicSwitch());

            ragObservabilityService.endNode(traceId, nodeId, question, answer, null);
            log.info("KnowledgeQA 完成, traceId={}, dialogAct={}, topicSwitch={}",
                    traceId, analysis.dialogAct(), analysis.topicSwitch());
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
        return executeStream(question, history, null, tokenConsumer);
    }

    public Map<String, Object> executeStream(String question,
                                             String history,
                                             KnowledgeRetrievalMode retrievalMode,
                                             Consumer<String> tokenConsumer) {
        return executeStream(question, history, retrievalMode, null, tokenConsumer);
    }

    public Map<String, Object> executeStream(String question,
                                             String history,
                                             KnowledgeRetrievalMode retrievalMode,
                                             String sessionId,
                                             Consumer<String> tokenConsumer) {
        String traceId = RAGTraceContext.getTraceId();
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
            RAGTraceContext.setTraceId(traceId);
        }

        String nodeId = UUID.randomUUID().toString();
        ragObservabilityService.startNode(traceId, nodeId, null, "KNOWLEDGE_QA", "Knowledge Q&A Streaming");

        try {
            TurnAnalysis analysis = analyzeTurnSafe(sessionId, question, history);
            KnowledgeContextPacket packet = knowledgeRetrievalCoordinator.retrieve(question, "", retrievalMode);
            String combinedContext;
            String dialogSignal = "";
            if (sessionId != null && !sessionId.isBlank()) {
                combinedContext = dynamicContextBuilder.buildDynamicContext(analysis, packet, sessionId);
                dialogSignal = dynamicContextBuilder.buildDialogSignal(analysis);
            } else {
                combinedContext = buildCombinedContext(packet);
            }

            Map<String, Object> vars = new HashMap<>();
            vars.put("question", question);
            vars.put("context", combinedContext);
            vars.put("imageContext", packet.imageContext());
            vars.put("evidence", packet.retrievalEvidence());
            vars.put("history", history != null ? history : "");
            vars.put("dialogSignal", dialogSignal);
            PromptManager.PromptPair pair = promptManager.renderSplit("knowledge-assistant", "knowledge-qa", vars);
            String answer = routingChatService.callStream(pair.systemPrompt(), pair.userPrompt(), ModelRouteType.GENERAL,
                    "知识问答-流式", tokenConsumer);

            if (sessionId != null && !sessionId.isBlank()) {
                topicTracker.generateDigestAsync(sessionId, analysis, packet.context());
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("answer", answer);
            result.put("sources", packet.retrievalEvidence());
            result.put("images", packet.retrievedImages());
            result.put("webFallbackUsed", packet.webFallbackUsed());
            result.put("localGraphUsed", packet.localGraphUsed());
            result.put("ragUsed", packet.ragUsed());
            result.put("fallbackReason", packet.fallbackReason());
            result.put("retrievalModeRequested", packet.retrievalModeRequested());
            result.put("retrievalModeResolved", packet.retrievalModeResolved());
            result.put("traceId", traceId);
            result.put("dialogAct", analysis.dialogAct().name());
            result.put("topicSwitch", analysis.topicSwitch());

            ragObservabilityService.endNode(traceId, nodeId, question, answer, null);
            log.info("KnowledgeQA流式完成, traceId={}, dialogAct={}, topicSwitch={}",
                    traceId, analysis.dialogAct(), analysis.topicSwitch());
            return result;
        } catch (Exception e) {
            ragObservabilityService.endNode(traceId, nodeId, question, null, e.getMessage());
            log.error("KnowledgeQA流式失败, traceId={}", traceId, e);
            throw e;
        }
    }

    private String buildCombinedContext(KnowledgeContextPacket packet) {
        StringBuilder contextBuilder = new StringBuilder(packet.context() == null ? "" : packet.context());
        if (packet.imageContext() != null && !packet.imageContext().isBlank()) {
            contextBuilder.append("\n\n相关图片说明:\n").append(packet.imageContext());
            contextBuilder.append("\n注意：你的回答可以引用上述图片，使用 [图N] 标记。系统会自动将对应图片内联展示给用户。");
        }
        return contextBuilder.toString();
    }

    private TurnAnalysis analyzeTurnSafe(String sessionId, String question, String history) {
        String fallbackTopic = extractFallbackTopic(question);
        if (sessionId == null || sessionId.isBlank()) {
            return TurnAnalysis.firstTurn(fallbackTopic);
        }
        try {
            return topicTracker.analyzeTurn(sessionId, question, history);
        } catch (Exception e) {
            log.warn("话题分析失败，降级为首轮: sessionId={}", sessionId, e);
            return TurnAnalysis.firstTurn(fallbackTopic);
        }
    }

    private String extractFallbackTopic(String question) {
        if (question == null || question.isBlank()) {
            return "未知话题";
        }
        String trimmed = question.trim();
        return trimmed.length() > 20 ? trimmed.substring(0, 20) : trimmed;
    }
}
