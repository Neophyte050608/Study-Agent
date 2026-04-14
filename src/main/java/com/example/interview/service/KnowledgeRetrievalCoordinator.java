package com.example.interview.service;

import com.example.interview.config.KnowledgeRetrievalProperties;
import com.example.interview.core.RAGTraceContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 统一知识检索协调器。
 *
 * <p>RAG_ONLY 直接走 RAG；LOCAL_GRAPH_* 走本地知识图；HYBRID_FUSION 会融合本地知识图与 RAG 结果。</p>
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
                traceRetrievalSnapshot(cached.packet(), TraceNodeDefinitions.RETRIEVAL_CACHE_HIT, "Retrieval Cache Hit", true);
                return cached.packet();
            }
        }
        if (effectiveRequestedMode == KnowledgeRetrievalMode.RAG_ONLY) {
            KnowledgeContextPacket packet = ragKnowledgeService.retrieve(
                    question,
                    userAnswer,
                    effectiveRequestedMode,
                    KnowledgeRetrievalMode.RAG_ONLY,
                    ""
            );
            return cacheAndReturn(question, userAnswer, effectiveRequestedMode, packet);
        }
        if (effectiveRequestedMode == KnowledgeRetrievalMode.HYBRID_FUSION) {
            return cacheAndReturn(question, userAnswer, effectiveRequestedMode, retrieveHybridFusion(question, userAnswer));
        }

        try {
            KnowledgeContextPacket packet = localGraphKnowledgeService.retrieve(question, effectiveRequestedMode);
            traceRetrievalSnapshot(packet, TraceNodeDefinitions.LOCAL_GRAPH_RETRIEVE, "Local Graph Retrieve", false);
            return cacheAndReturn(question, userAnswer, effectiveRequestedMode, packet);
        } catch (LocalGraphRetrievalException e) {
            if (effectiveRequestedMode == KnowledgeRetrievalMode.LOCAL_GRAPH_ONLY) {
                throw e;
            }
            traceFallback(e);
            KnowledgeContextPacket packet = ragKnowledgeService.retrieve(
                    question,
                    userAnswer,
                    effectiveRequestedMode,
                    KnowledgeRetrievalMode.RAG_ONLY,
                    e.getFailureReason().name()
            );
            return cacheAndReturn(question, userAnswer, effectiveRequestedMode, packet);
        }
    }

    private KnowledgeContextPacket retrieveHybridFusion(String question, String userAnswer) {
        String traceId = RAGTraceContext.getTraceId();
        String parentNodeId = RAGTraceContext.getCurrentNodeId();
        CompletableFuture<KnowledgeContextPacket> localFuture = CompletableFuture.supplyAsync(
                wrapWithTraceContext(traceId, parentNodeId,
                        () -> localGraphKnowledgeService.retrieve(question, KnowledgeRetrievalMode.HYBRID_FUSION))
        );
        CompletableFuture<KnowledgeContextPacket> ragFuture = CompletableFuture.supplyAsync(
                wrapWithTraceContext(traceId, parentNodeId,
                        () -> ragKnowledgeService.retrieve(
                                question,
                                userAnswer,
                                KnowledgeRetrievalMode.HYBRID_FUSION,
                                KnowledgeRetrievalMode.RAG_ONLY,
                                ""
                        ))
        );
        KnowledgeContextPacket ragPacket = ragFuture.join();
        try {
            KnowledgeContextPacket localPacket = localFuture.join();
            traceRetrievalSnapshot(localPacket, TraceNodeDefinitions.LOCAL_GRAPH_RETRIEVE, "Local Graph Retrieve", false);
            traceRetrievalSnapshot(ragPacket, TraceNodeDefinitions.DOC_RETRIEVE, "RAG Retrieve", false);
            KnowledgeContextPacket fusedPacket = mergeHybridPackets(question, localPacket, ragPacket);
            traceRetrievalSnapshot(fusedPacket, TraceNodeDefinitions.HYBRID_FUSION_RETRIEVE, "Hybrid Fusion Retrieve", false);
            return fusedPacket;
        } catch (CompletionException e) {
            LocalGraphRetrievalException localException = unwrapLocalGraphException(e);
            if (localException == null) {
                throw e;
            }
            traceRetrievalSnapshot(ragPacket, TraceNodeDefinitions.DOC_RETRIEVE, "RAG Retrieve", false);
            traceRetrievalSnapshot(ragPacket, TraceNodeDefinitions.HYBRID_FUSION_RETRIEVE, "Hybrid Fusion Retrieve", false);
            traceFallback(localException);
            return new KnowledgeContextPacket(
                    KnowledgeRetrievalMode.HYBRID_FUSION,
                    ragPacket.retrievalModeResolved(),
                    false,
                    ragPacket.ragUsed(),
                    localException.getFailureReason().name(),
                    ragPacket.retrievalQuery(),
                    ragPacket.context(),
                    ragPacket.imageContext(),
                    ragPacket.retrievalEvidence(),
                    ragPacket.retrievedImages(),
                    ragPacket.webFallbackUsed(),
                    ragPacket.retrievedDocCount(),
                    ragPacket.retrievedDocumentRefs()
            );
        } catch (LocalGraphRetrievalException e) {
            traceFallback(e);
            traceRetrievalSnapshot(ragPacket, TraceNodeDefinitions.DOC_RETRIEVE, "RAG Retrieve", false);
            traceRetrievalSnapshot(ragPacket, TraceNodeDefinitions.HYBRID_FUSION_RETRIEVE, "Hybrid Fusion Retrieve", false);
            return new KnowledgeContextPacket(
                    KnowledgeRetrievalMode.HYBRID_FUSION,
                    ragPacket.retrievalModeResolved(),
                    false,
                    ragPacket.ragUsed(),
                    e.getFailureReason().name(),
                    ragPacket.retrievalQuery(),
                    ragPacket.context(),
                    ragPacket.imageContext(),
                    ragPacket.retrievalEvidence(),
                    ragPacket.retrievedImages(),
                    ragPacket.webFallbackUsed(),
                    ragPacket.retrievedDocCount(),
                    ragPacket.retrievedDocumentRefs()
            );
        }
    }

    private KnowledgeContextPacket mergeHybridPackets(String question,
                                                      KnowledgeContextPacket localPacket,
                                                      KnowledgeContextPacket ragPacket) {
        List<ImageService.ImageResult> mergedImages = mergeImages(localPacket == null ? List.of() : localPacket.retrievedImages(),
                ragPacket == null ? List.of() : ragPacket.retrievedImages());
        String mergedContext = buildHybridContext(localPacket, ragPacket);
        String mergedEvidence = mergeEvidence(localPacket == null ? "" : localPacket.retrievalEvidence(),
                ragPacket == null ? "" : ragPacket.retrievalEvidence());
        List<String> mergedRefs = mergeDocumentRefs(localPacket == null ? List.of() : localPacket.retrievedDocumentRefs(),
                ragPacket == null ? List.of() : ragPacket.retrievedDocumentRefs());
        int mergedDocCount = mergeDocCount(localPacket, ragPacket, mergedRefs);
        return new KnowledgeContextPacket(
                KnowledgeRetrievalMode.HYBRID_FUSION,
                KnowledgeRetrievalMode.HYBRID_FUSION,
                localPacket != null && localPacket.localGraphUsed(),
                ragPacket != null && ragPacket.ragUsed(),
                "",
                resolveHybridQuery(question, ragPacket, localPacket),
                mergedContext,
                buildImageContext(mergedImages),
                mergedEvidence,
                mergedImages,
                ragPacket != null && ragPacket.webFallbackUsed(),
                mergedDocCount,
                mergedRefs
        );
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
        ragObservabilityService.startNode(
                traceId,
                nodeId,
                RAGTraceContext.getCurrentNodeId(),
                TraceNodeDefinitions.LOCAL_GRAPH_FALLBACK.nodeType(),
                TraceNodeDefinitions.LOCAL_GRAPH_FALLBACK.nodeName()
        );
        ragObservabilityService.endNode(
                traceId,
                nodeId,
                exception.getMessage(),
                "fallbackTo=RAG_ONLY",
                exception.getFailureReason().name(),
                null,
                new RAGObservabilityService.NodeDetails(
                        1,
                        KnowledgeRetrievalMode.RAG_ONLY.name(),
                        null,
                        false,
                        0,
                        List.of(),
                        null,
                        null,
                        exception.getFailureReason().name(),
                        null,
                        null,
                        null
                )
        );
    }

    private void traceRetrievalSnapshot(KnowledgeContextPacket packet,
                                        TraceNodeDefinition definition,
                                        String outputLabel,
                                        boolean cacheHit) {
        if (packet == null) {
            return;
        }
        String traceId = RAGTraceContext.getTraceId();
        String nodeId = UUID.randomUUID().toString();
        ragObservabilityService.startNode(
                traceId,
                nodeId,
                RAGTraceContext.getCurrentNodeId(),
                definition.nodeType(),
                definition.nodeName()
        );
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("mode", packet.retrievalModeResolved());
        output.put("cacheHit", cacheHit);
        output.put("docCount", packet.retrievedDocCount());
        output.put("retrievedDocs", packet.retrievedDocCount());
        output.put("docRefs", packet.retrievedDocumentRefs());
        ragObservabilityService.endNode(
                traceId,
                nodeId,
                packet.retrievalQuery(),
                outputLabel + output,
                null,
                new RAGObservabilityService.NodeMetrics(packet.retrievedDocCount(), packet.webFallbackUsed()),
                new RAGObservabilityService.NodeDetails(
                        1,
                        packet.retrievalModeResolved() == null ? null : packet.retrievalModeResolved().name(),
                        null,
                        cacheHit,
                        packet.retrievedDocCount(),
                        packet.retrievedDocumentRefs(),
                        null,
                        null,
                        packet.fallbackReason(),
                        null,
                        null,
                        null
                )
        );
    }

    private record CachedRetrieval(KnowledgeContextPacket packet, long expireAtMs) {
        private boolean isExpired() {
            return System.currentTimeMillis() >= expireAtMs;
        }
    }

    private String resolveHybridQuery(String question,
                                      KnowledgeContextPacket ragPacket,
                                      KnowledgeContextPacket localPacket) {
        if (ragPacket != null && ragPacket.retrievalQuery() != null && !ragPacket.retrievalQuery().isBlank()) {
            return ragPacket.retrievalQuery();
        }
        if (localPacket != null && localPacket.retrievalQuery() != null && !localPacket.retrievalQuery().isBlank()) {
            return localPacket.retrievalQuery();
        }
        return question == null ? "" : question;
    }

    private String buildHybridContext(KnowledgeContextPacket localPacket, KnowledgeContextPacket ragPacket) {
        List<String> sections = new ArrayList<>();
        if (localPacket != null && localPacket.context() != null && !localPacket.context().isBlank()) {
            sections.add("[本地知识图]\n" + localPacket.context().trim());
        }
        if (ragPacket != null && ragPacket.context() != null && !ragPacket.context().isBlank()) {
            sections.add("[RAG检索]\n" + ragPacket.context().trim());
        }
        return String.join("\n\n", sections).trim();
    }

    private String mergeEvidence(String localEvidence, String ragEvidence) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        addEvidenceLines(merged, localEvidence);
        addEvidenceLines(merged, ragEvidence);
        if (merged.isEmpty()) {
            return "";
        }
        List<String> normalized = new ArrayList<>();
        int index = 1;
        for (String line : merged) {
            String payload = line.replaceFirst("^\\d+\\.\\s*", "").trim();
            if (!payload.isBlank()) {
                normalized.add(index++ + ". " + payload);
            }
        }
        return String.join("\n", normalized);
    }

    private void addEvidenceLines(Collection<String> target, String evidence) {
        if (evidence == null || evidence.isBlank()) {
            return;
        }
        for (String line : evidence.split("\\R")) {
            String trimmed = line == null ? "" : line.trim();
            if (!trimmed.isBlank()) {
                target.add(trimmed);
            }
        }
    }

    private List<String> mergeDocumentRefs(List<String> localRefs, List<String> ragRefs) {
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        if (localRefs != null) {
            refs.addAll(localRefs.stream().filter(Objects::nonNull).map(String::trim).filter(item -> !item.isBlank()).toList());
        }
        if (ragRefs != null) {
            refs.addAll(ragRefs.stream().filter(Objects::nonNull).map(String::trim).filter(item -> !item.isBlank()).toList());
        }
        return refs.stream().limit(8).toList();
    }

    private int mergeDocCount(KnowledgeContextPacket localPacket,
                              KnowledgeContextPacket ragPacket,
                              List<String> mergedRefs) {
        int localCount = localPacket == null ? 0 : localPacket.retrievedDocCount();
        int ragCount = ragPacket == null ? 0 : ragPacket.retrievedDocCount();
        int merged = Math.max(mergedRefs == null ? 0 : mergedRefs.size(), localCount + ragCount);
        return Math.max(0, merged);
    }

    private List<ImageService.ImageResult> mergeImages(List<ImageService.ImageResult> localImages,
                                                       List<ImageService.ImageResult> ragImages) {
        Map<String, ImageService.ImageResult> merged = new LinkedHashMap<>();
        mergeImageList(merged, localImages);
        mergeImageList(merged, ragImages);
        return merged.values().stream()
                .sorted((a, b) -> Double.compare(b.relevanceScore(), a.relevanceScore()))
                .limit(4)
                .toList();
    }

    private void mergeImageList(Map<String, ImageService.ImageResult> target,
                                List<ImageService.ImageResult> images) {
        if (images == null || images.isEmpty()) {
            return;
        }
        for (ImageService.ImageResult image : images) {
            if (image == null || image.imageId() == null || image.imageId().isBlank()) {
                continue;
            }
            target.compute(image.imageId(), (key, existing) -> existing == null || image.relevanceScore() > existing.relevanceScore()
                    ? image
                    : existing);
        }
    }

    private String buildImageContext(List<ImageService.ImageResult> images) {
        if (images == null || images.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        int index = 1;
        for (ImageService.ImageResult image : images) {
            builder.append("[图").append(index++).append("] ")
                    .append(image.summaryText() == null ? image.imageName() : image.summaryText())
                    .append(" - 来源: ").append(image.imageName())
                    .append("\n");
        }
        return builder.toString().trim();
    }

    private LocalGraphRetrievalException unwrapLocalGraphException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof LocalGraphRetrievalException exception) {
                return exception;
            }
            current = current.getCause();
        }
        return null;
    }

    private java.util.function.Supplier<KnowledgeContextPacket> wrapWithTraceContext(String traceId,
                                                                                     String parentNodeId,
                                                                                     java.util.function.Supplier<KnowledgeContextPacket> supplier) {
        return () -> {
            RAGTraceContext.setTraceId(traceId);
            if (parentNodeId != null && !parentNodeId.isBlank()) {
                RAGTraceContext.pushNode(parentNodeId);
            }
            try {
                return supplier.get();
            } finally {
                RAGTraceContext.clear();
            }
        };
    }
}
