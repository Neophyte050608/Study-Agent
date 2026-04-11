package com.example.interview.service;

import com.example.interview.config.KnowledgeRetrievalProperties;
import com.example.interview.core.RAGTraceContext;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

/**
 * 统一知识检索协调器。
 *
 * <p>第一阶段仅提供模式壳层，所有模式暂时统一回落到 RAG 适配层。
 * 这样可以先完成上层解耦，再逐步接入本地知识图能力。</p>
 */
@Service
public class KnowledgeRetrievalCoordinator {

    private static final int RETRIEVAL_CACHE_MAX_SIZE = 128;

    private final KnowledgeRetrievalProperties properties;
    private final RagKnowledgeService ragKnowledgeService;
    private final LocalGraphKnowledgeService localGraphKnowledgeService;
    private final RAGObservabilityService ragObservabilityService;
    private final ConcurrentHashMap<String, CachedRetrieval> retrievalCache = new ConcurrentHashMap<>();

    public KnowledgeRetrievalCoordinator(KnowledgeRetrievalProperties properties,
                                         RagKnowledgeService ragKnowledgeService,
                                         LocalGraphKnowledgeService localGraphKnowledgeService,
                                         RAGObservabilityService ragObservabilityService) {
        this.properties = properties;
        this.ragKnowledgeService = ragKnowledgeService;
        this.localGraphKnowledgeService = localGraphKnowledgeService;
        this.ragObservabilityService = ragObservabilityService;
    }

    public KnowledgeContextPacket retrieve(String question, String userAnswer) {
        return retrieve(question, userAnswer, null);
    }

    public KnowledgeContextPacket retrieve(String question,
                                           String userAnswer,
                                           KnowledgeRetrievalMode requestedMode) {
        KnowledgeRetrievalMode effectiveRequestedMode = requestedMode == null
                ? properties.getDefaultMode()
                : requestedMode;
        if (properties.isRetrievalCacheEnabled()) {
            String cacheKey = buildCacheKey(question, userAnswer, effectiveRequestedMode);
            CachedRetrieval cached = retrievalCache.get(cacheKey);
            if (cached != null && !cached.isExpired()) {
                return cached.packet();
            }
        }
        if (effectiveRequestedMode == KnowledgeRetrievalMode.RAG_ONLY) {
            return cacheAndReturn(question, userAnswer, effectiveRequestedMode, ragKnowledgeService.retrieve(
                    question,
                    userAnswer,
                    effectiveRequestedMode,
                    KnowledgeRetrievalMode.RAG_ONLY,
                    ""
            ));
        }

        try {
            return cacheAndReturn(question, userAnswer, effectiveRequestedMode,
                    localGraphKnowledgeService.retrieve(question, effectiveRequestedMode));
        } catch (LocalGraphRetrievalException e) {
            if (effectiveRequestedMode == KnowledgeRetrievalMode.LOCAL_GRAPH_ONLY) {
                throw e;
            }
            traceFallback(e);
            return cacheAndReturn(question, userAnswer, effectiveRequestedMode, ragKnowledgeService.retrieve(
                    question,
                    userAnswer,
                    effectiveRequestedMode,
                    KnowledgeRetrievalMode.RAG_ONLY,
                    e.getFailureReason().name()
            ));
        }
    }

    private KnowledgeContextPacket cacheAndReturn(String question,
                                                  String userAnswer,
                                                  KnowledgeRetrievalMode mode,
                                                  KnowledgeContextPacket packet) {
        if (properties.isRetrievalCacheEnabled()) {
            String cacheKey = buildCacheKey(question, userAnswer, mode);
            long expireAt = System.currentTimeMillis() + properties.getRetrievalCacheTtlSeconds() * 1000L;
            retrievalCache.put(cacheKey, new CachedRetrieval(packet, expireAt));
            if (retrievalCache.size() > RETRIEVAL_CACHE_MAX_SIZE) {
                retrievalCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
            }
        }
        return packet;
    }

    private String buildCacheKey(String question, String userAnswer, KnowledgeRetrievalMode mode) {
        String normalizedQuestion = question == null ? "" : question.trim().toLowerCase();
        String normalizedAnswer = userAnswer == null ? "" : userAnswer.trim().toLowerCase();
        String modeStr = mode == null ? "RAG_ONLY" : mode.name();
        return normalizedQuestion + "|" + normalizedAnswer + "|" + modeStr;
    }

    private void traceFallback(LocalGraphRetrievalException exception) {
        String traceId = RAGTraceContext.getTraceId();
        String nodeId = UUID.randomUUID().toString();
        ragObservabilityService.startNode(traceId, nodeId, RAGTraceContext.getCurrentNodeId(), "LOCAL_GRAPH_FALLBACK", "Local Graph Fallback");
        ragObservabilityService.endNode(
                traceId,
                nodeId,
                exception.getMessage(),
                "fallbackTo=RAG_ONLY",
                exception.getFailureReason().name()
        );
    }

    private record CachedRetrieval(KnowledgeContextPacket packet, long expireAtMs) {
        private boolean isExpired() {
            return System.currentTimeMillis() >= expireAtMs;
        }
    }
}
