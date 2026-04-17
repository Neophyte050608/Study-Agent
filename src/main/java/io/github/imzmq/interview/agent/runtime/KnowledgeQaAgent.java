package io.github.imzmq.interview.agent.runtime;

import io.github.imzmq.interview.core.trace.RAGTraceContext;
import io.github.imzmq.interview.modelrouting.core.ModelRouteType;
import io.github.imzmq.interview.modelrouting.core.RoutingChatService;
import io.github.imzmq.interview.knowledge.domain.KnowledgeContextPacket;
import io.github.imzmq.interview.knowledge.application.retrieval.KnowledgeRetrievalCoordinator;
import io.github.imzmq.interview.knowledge.domain.KnowledgeRetrievalMode;
import io.github.imzmq.interview.chat.application.PromptManager;
import io.github.imzmq.interview.knowledge.application.observability.RAGObservabilityService;
import io.github.imzmq.interview.knowledge.application.observability.TraceService;
import io.github.imzmq.interview.knowledge.application.context.ConversationTopicTracker;
import io.github.imzmq.interview.knowledge.domain.DialogAct;
import io.github.imzmq.interview.knowledge.application.context.DynamicKnowledgeContextBuilder;
import io.github.imzmq.interview.knowledge.domain.TurnAnalysis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
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
    private final TraceService traceService;
    private final ConversationTopicTracker topicTracker;
    private final DynamicKnowledgeContextBuilder dynamicContextBuilder;
    private final Executor ragRetrieveExecutor;

    public KnowledgeQaAgent(KnowledgeRetrievalCoordinator knowledgeRetrievalCoordinator,
                            RoutingChatService routingChatService,
                            PromptManager promptManager,
                            RAGObservabilityService ragObservabilityService,
                            TraceService traceService,
                            ConversationTopicTracker topicTracker,
                            DynamicKnowledgeContextBuilder dynamicContextBuilder,
                            @Qualifier("ragRetrieveExecutor") Executor ragRetrieveExecutor) {
        this.knowledgeRetrievalCoordinator = knowledgeRetrievalCoordinator;
        this.routingChatService = routingChatService;
        this.promptManager = promptManager;
        this.ragObservabilityService = ragObservabilityService;
        this.traceService = traceService;
        this.topicTracker = topicTracker;
        this.dynamicContextBuilder = dynamicContextBuilder;
        this.ragRetrieveExecutor = ragRetrieveExecutor;
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
        return execute(question, history, retrievalMode, sessionId, null);
    }

    public Map<String, Object> execute(String question,
                                       String history,
                                       KnowledgeRetrievalMode retrievalMode,
                                       String sessionId,
                                       Map<String, Object> precomputedTurnAnalysis) {
        String traceId = RAGTraceContext.getTraceId();
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
            RAGTraceContext.setTraceId(traceId);
        }

        String nodeId = UUID.randomUUID().toString();
        ragObservabilityService.startNode(traceId, nodeId, null, "KNOWLEDGE_QA", "Knowledge Q&A");

        try {
            String currentTraceId = RAGTraceContext.getTraceId();
            String currentNodeId = RAGTraceContext.getCurrentNodeId();
            CompletableFuture<TurnAnalysis> analysisFuture = CompletableFuture.supplyAsync(
                    wrapWithTraceContext(currentTraceId, currentNodeId,
                            () -> resolveAnalysis(sessionId, question, history, precomputedTurnAnalysis)),
                    ragRetrieveExecutor
            );
            CompletableFuture<KnowledgeContextPacket> packetFuture = CompletableFuture.supplyAsync(
                    wrapWithTraceContext(currentTraceId, currentNodeId,
                            () -> knowledgeRetrievalCoordinator.retrieve(question, "", retrievalMode)),
                    ragRetrieveExecutor
            );
            TurnAnalysis analysis = analysisFuture.join();
            KnowledgeContextPacket packet = packetFuture.join();
            String contextPolicy = resolveContextPolicy(precomputedTurnAnalysis, analysis);
            String combinedContext;
            String dialogSignal = "";
            if (sessionId != null && !sessionId.isBlank()) {
                combinedContext = dynamicContextBuilder.buildDynamicContext(contextPolicy, analysis, packet, sessionId);
                dialogSignal = dynamicContextBuilder.buildDialogSignal(contextPolicy, analysis);
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
            result.put("contextPolicy", contextPolicy);

            ragObservabilityService.endNode(traceId, nodeId, question, answer, null);
            log.info("KnowledgeQA 完成, traceId={}, dialogAct={}, topicSwitch={}, contextPolicy={}",
                    traceId, analysis.dialogAct(), analysis.topicSwitch(), contextPolicy);
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
        return executeStream(question, history, retrievalMode, sessionId, tokenConsumer, null);
    }

    public Map<String, Object> executeStream(String question,
                                             String history,
                                             KnowledgeRetrievalMode retrievalMode,
                                             String sessionId,
                                             Consumer<String> tokenConsumer,
                                             Map<String, Object> precomputedTurnAnalysis) {
        String traceId = RAGTraceContext.getTraceId();
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
            RAGTraceContext.setTraceId(traceId);
        }

        String nodeId = UUID.randomUUID().toString();
        ragObservabilityService.startNode(traceId, nodeId, null, "KNOWLEDGE_QA", "Knowledge Q&A Streaming");

        try {
            String currentTraceId = RAGTraceContext.getTraceId();
            String currentNodeId = RAGTraceContext.getCurrentNodeId();
            CompletableFuture<TurnAnalysis> analysisFuture = CompletableFuture.supplyAsync(
                    wrapWithTraceContext(currentTraceId, currentNodeId,
                            () -> resolveAnalysis(sessionId, question, history, precomputedTurnAnalysis)),
                    ragRetrieveExecutor
            );
            CompletableFuture<KnowledgeContextPacket> packetFuture = CompletableFuture.supplyAsync(
                    wrapWithTraceContext(currentTraceId, currentNodeId,
                            () -> knowledgeRetrievalCoordinator.retrieve(question, "", retrievalMode)),
                    ragRetrieveExecutor
            );
            TurnAnalysis analysis = analysisFuture.join();
            KnowledgeContextPacket packet = packetFuture.join();
            String contextPolicy = resolveContextPolicy(precomputedTurnAnalysis, analysis);
            String combinedContext;
            String dialogSignal = "";
            if (sessionId != null && !sessionId.isBlank()) {
                combinedContext = dynamicContextBuilder.buildDynamicContext(contextPolicy, analysis, packet, sessionId);
                dialogSignal = dynamicContextBuilder.buildDialogSignal(contextPolicy, analysis);
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
            String answer = routingChatService.callStreamWithTrace(
                    pair.systemPrompt(),
                    pair.userPrompt(),
                    ModelRouteType.GENERAL,
                    "知识问答-流式",
                    tokenConsumer,
                    traceService
            );

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
            result.put("contextPolicy", contextPolicy);

            ragObservabilityService.endNode(traceId, nodeId, question, answer, null);
            log.info("KnowledgeQA流式完成, traceId={}, dialogAct={}, topicSwitch={}, contextPolicy={}",
                    traceId, analysis.dialogAct(), analysis.topicSwitch(), contextPolicy);
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

    private <T> java.util.function.Supplier<T> wrapWithTraceContext(String traceId,
                                                                    String parentNodeId,
                                                                    java.util.function.Supplier<T> action) {
        return () -> {
            RAGTraceContext.setTraceId(traceId);
            if (parentNodeId != null && !parentNodeId.isBlank()) {
                RAGTraceContext.pushNode(parentNodeId);
            }
            try {
                return action.get();
            } finally {
                RAGTraceContext.clear();
            }
        };
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

    private TurnAnalysis resolveAnalysis(String sessionId,
                                         String question,
                                         String history,
                                         Map<String, Object> precomputedTurnAnalysis) {
        TurnAnalysis parsed = parsePrecomputedTurnAnalysis(precomputedTurnAnalysis, question);
        if (parsed != null) {
            return parsed;
        }
        return analyzeTurnSafe(sessionId, question, history);
    }

    private TurnAnalysis parsePrecomputedTurnAnalysis(Map<String, Object> hints, String question) {
        if (hints == null || hints.isEmpty()) {
            return null;
        }
        String currentTopic = textOf(hints.get("currentTopic"));
        if (currentTopic.isBlank()) {
            currentTopic = extractFallbackTopic(question);
        }
        String dialogActRaw = textOf(hints.get("dialogAct"));
        String contextPolicy = normalizeContextPolicy(textOf(hints.get("contextPolicy")));
        if (dialogActRaw.isBlank() && !contextPolicy.isBlank()) {
            dialogActRaw = dialogActFromContextPolicy(contextPolicy);
        }
        if (dialogActRaw.isBlank()) {
            return null;
        }
        boolean topicSwitch = hints.containsKey("topicSwitch")
                ? boolOf(hints.get("topicSwitch"))
                : inferTopicSwitchFromPolicy(contextPolicy);
        double infoNovelty = hints.containsKey("infoNovelty")
                ? clampNovelty(doubleOf(hints.get("infoNovelty"), 0.5))
                : defaultNoveltyForPolicy(contextPolicy);
        String previousTopic = textOf(hints.get("previousTopic"));
        if (previousTopic.isBlank()) {
            previousTopic = currentTopic;
        }
        DialogAct dialogAct = DialogAct.fromString(dialogActRaw);
        return new TurnAnalysis(topicSwitch, dialogAct, infoNovelty, currentTopic, previousTopic);
    }

    private String resolveContextPolicy(Map<String, Object> hints, TurnAnalysis analysis) {
        String fromHints = normalizeContextPolicy(textOf(hints == null ? null : hints.get("contextPolicy")));
        if (!fromHints.isBlank()) {
            return fromHints;
        }
        if (analysis == null) {
            return "SAFE_MIN";
        }
        return switch (analysis.dialogAct()) {
            case NEW_QUESTION, COMPARISON -> "SWITCH";
            case RETURN -> "RETURN";
            case SUMMARY -> "SUMMARY";
            case FOLLOW_UP, CLARIFICATION -> analysis.topicSwitch() ? "SWITCH" : "CONTINUE";
        };
    }

    private String normalizeContextPolicy(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase();
        return switch (value) {
            case "CONTINUE", "SWITCH", "RETURN", "SUMMARY", "SAFE_MIN" -> value;
            default -> "";
        };
    }

    private String dialogActFromContextPolicy(String contextPolicy) {
        return switch (contextPolicy) {
            case "SWITCH" -> "NEW_QUESTION";
            case "RETURN" -> "RETURN";
            case "SUMMARY" -> "SUMMARY";
            default -> "FOLLOW_UP";
        };
    }

    private boolean inferTopicSwitchFromPolicy(String contextPolicy) {
        return "SWITCH".equals(contextPolicy);
    }

    private double defaultNoveltyForPolicy(String contextPolicy) {
        return switch (contextPolicy) {
            case "SWITCH" -> 0.9;
            case "SUMMARY" -> 0.2;
            default -> 0.5;
        };
    }

    private String textOf(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private boolean boolOf(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value != null) {
            String text = String.valueOf(value).trim();
            return "true".equalsIgnoreCase(text) || "1".equals(text);
        }
        return false;
    }

    private double doubleOf(Object value, double defaultValue) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private double clampNovelty(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private String extractFallbackTopic(String question) {
        if (question == null || question.isBlank()) {
            return "未知话题";
        }
        String trimmed = question.trim();
        return trimmed.length() > 20 ? trimmed.substring(0, 20) : trimmed;
    }
}











