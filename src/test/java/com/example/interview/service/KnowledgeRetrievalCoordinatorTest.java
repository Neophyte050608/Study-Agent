package com.example.interview.service;

import com.example.interview.config.KnowledgeRetrievalProperties;
import com.example.interview.config.ObservabilitySwitchProperties;
import com.example.interview.core.RAGTraceContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class KnowledgeRetrievalCoordinatorTest {

    @Test
    void shouldKeepRagOnlyAsResolvedModeWhenRequestedModeIsRagOnly() {
        KnowledgeRetrievalProperties properties = new KnowledgeRetrievalProperties();
        properties.setDefaultMode(KnowledgeRetrievalMode.RAG_ONLY);
        FakeRagKnowledgeService ragKnowledgeService = new FakeRagKnowledgeService();
        KnowledgeRetrievalCoordinator coordinator = new KnowledgeRetrievalCoordinator(
                properties,
                ragKnowledgeService,
                new FakeLocalGraphKnowledgeService(),
                new RAGObservabilityService(new ObservabilitySwitchProperties())
        );

        KnowledgeContextPacket packet = coordinator.retrieve("什么是缓存雪崩", "", KnowledgeRetrievalMode.RAG_ONLY);

        assertEquals(KnowledgeRetrievalMode.RAG_ONLY, packet.retrievalModeRequested());
        assertEquals(KnowledgeRetrievalMode.RAG_ONLY, packet.retrievalModeResolved());
    }

    @Test
    void shouldTemporarilyDowngradeUnimplementedModeToRagOnly() {
        KnowledgeRetrievalProperties properties = new KnowledgeRetrievalProperties();
        properties.setDefaultMode(KnowledgeRetrievalMode.LOCAL_GRAPH_FIRST);
        FakeRagKnowledgeService ragKnowledgeService = new FakeRagKnowledgeService();
        KnowledgeRetrievalCoordinator coordinator = new KnowledgeRetrievalCoordinator(
                properties,
                ragKnowledgeService,
                new FakeLocalGraphKnowledgeService(true, LocalGraphFailureReason.LOCAL_RETRIEVAL_NOT_IMPLEMENTED),
                new RAGObservabilityService(new ObservabilitySwitchProperties())
        );

        KnowledgeContextPacket packet = coordinator.retrieve("Redis 为什么快", "", KnowledgeRetrievalMode.LOCAL_GRAPH_FIRST);

        assertEquals(KnowledgeRetrievalMode.LOCAL_GRAPH_FIRST, packet.retrievalModeRequested());
        assertEquals(KnowledgeRetrievalMode.RAG_ONLY, packet.retrievalModeResolved());
        assertEquals(LocalGraphFailureReason.LOCAL_RETRIEVAL_NOT_IMPLEMENTED.name(), packet.fallbackReason());
    }

    @Test
    void shouldThrowWhenLocalGraphOnlyAndIndexIsNotConfigured() {
        KnowledgeRetrievalProperties properties = new KnowledgeRetrievalProperties();
        properties.setDefaultMode(KnowledgeRetrievalMode.LOCAL_GRAPH_ONLY);
        FakeRagKnowledgeService ragKnowledgeService = new FakeRagKnowledgeService();
        KnowledgeRetrievalCoordinator coordinator = new KnowledgeRetrievalCoordinator(
                properties,
                ragKnowledgeService,
                new FakeLocalGraphKnowledgeService(true, LocalGraphFailureReason.INDEX_NOT_CONFIGURED),
                new RAGObservabilityService(new ObservabilitySwitchProperties())
        );

        try {
            coordinator.retrieve("Redis 为什么快", "", KnowledgeRetrievalMode.LOCAL_GRAPH_ONLY);
        } catch (LocalGraphRetrievalException e) {
            assertEquals(LocalGraphFailureReason.INDEX_NOT_CONFIGURED, e.getFailureReason());
            return;
        }
        throw new AssertionError("Expected LocalGraphRetrievalException");
    }

    @Test
    void shouldTraceLocalGraphRetrievalWithRetrievedDocCount() {
        KnowledgeRetrievalProperties properties = new KnowledgeRetrievalProperties();
        properties.setDefaultMode(KnowledgeRetrievalMode.LOCAL_GRAPH_FIRST);
        properties.setRetrievalCacheEnabled(false);
        ObservabilitySwitchProperties observabilityProperties = new ObservabilitySwitchProperties();
        observabilityProperties.setRagTraceEnabled(true);
        RAGObservabilityService observabilityService = new RAGObservabilityService(observabilityProperties);
        KnowledgeRetrievalCoordinator coordinator = new KnowledgeRetrievalCoordinator(
                properties,
                new FakeRagKnowledgeService(),
                new FakeLocalGraphKnowledgeService(),
                observabilityService
        );

        RAGTraceContext.setTraceId("trace-local-graph");
        try {
            coordinator.retrieve("Redis 为什么快", "", KnowledgeRetrievalMode.LOCAL_GRAPH_FIRST);
            RAGObservabilityService.RAGTrace trace = observabilityService.getTraceDetail("trace-local-graph");
            assertNotNull(trace);
            RAGObservabilityService.RAGTraceNode node = trace.nodes().stream()
                    .filter(item -> "RETRIEVAL".equals(item.nodeType()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(node);
            assertEquals("LOCAL_GRAPH_RETRIEVE", node.nodeName());
            assertEquals(3, node.metrics().retrievedDocs());
            assertNotNull(node.details());
            assertEquals(KnowledgeRetrievalMode.LOCAL_GRAPH_FIRST.name(), node.details().retrievalMode());
            assertEquals(2, node.details().retrievedDocumentRefs().size());
        } finally {
            RAGTraceContext.clear();
        }
    }

    @Test
    void shouldKeepLocalGraphImagesWhenHybridFusionSucceeds() {
        KnowledgeRetrievalProperties properties = new KnowledgeRetrievalProperties();
        properties.setDefaultMode(KnowledgeRetrievalMode.HYBRID_FUSION);
        KnowledgeRetrievalCoordinator coordinator = new KnowledgeRetrievalCoordinator(
                properties,
                new FakeRagKnowledgeService(),
                new FakeLocalGraphKnowledgeService(),
                new RAGObservabilityService(new ObservabilitySwitchProperties())
        );

        KnowledgeContextPacket packet = coordinator.retrieve("Redis 为什么快", "", KnowledgeRetrievalMode.HYBRID_FUSION);

        assertEquals(KnowledgeRetrievalMode.HYBRID_FUSION, packet.retrievalModeResolved());
        assertTrue(packet.localGraphUsed());
        assertTrue(packet.ragUsed());
        assertTrue(packet.context().contains("[本地知识图]"));
        assertTrue(packet.context().contains("[RAG检索]"));
        assertTrue(packet.retrievalEvidence().contains("[local_graph:Redis/AOF]"));
        assertTrue(packet.retrievalEvidence().contains("[obsidian:redis.md]"));
        assertEquals(2, packet.retrievedImages().size());
        assertFalse(packet.retrievedImages().isEmpty());
    }

    @Test
    void shouldFallbackToRagOnlyWhenHybridFusionLocalGraphFails() {
        KnowledgeRetrievalProperties properties = new KnowledgeRetrievalProperties();
        properties.setDefaultMode(KnowledgeRetrievalMode.HYBRID_FUSION);
        KnowledgeRetrievalCoordinator coordinator = new KnowledgeRetrievalCoordinator(
                properties,
                new FakeRagKnowledgeService(),
                new FakeLocalGraphKnowledgeService(true, LocalGraphFailureReason.ROUTING_EMPTY),
                new RAGObservabilityService(new ObservabilitySwitchProperties())
        );

        KnowledgeContextPacket packet = coordinator.retrieve("Redis 为什么快", "", KnowledgeRetrievalMode.HYBRID_FUSION);

        assertEquals(KnowledgeRetrievalMode.RAG_ONLY, packet.retrievalModeResolved());
        assertEquals(LocalGraphFailureReason.ROUTING_EMPTY.name(), packet.fallbackReason());
        assertTrue(packet.ragUsed());
        assertFalse(packet.localGraphUsed());
    }

    @Test
    void shouldTraceCacheHitWithRetrievedDocCount() {
        KnowledgeRetrievalProperties properties = new KnowledgeRetrievalProperties();
        properties.setDefaultMode(KnowledgeRetrievalMode.RAG_ONLY);
        properties.setRetrievalCacheEnabled(true);
        properties.setRetrievalCacheTtlSeconds(60);
        ObservabilitySwitchProperties observabilityProperties = new ObservabilitySwitchProperties();
        observabilityProperties.setRagTraceEnabled(true);
        RAGObservabilityService observabilityService = new RAGObservabilityService(observabilityProperties);
        KnowledgeRetrievalCoordinator coordinator = new KnowledgeRetrievalCoordinator(
                properties,
                new FakeRagKnowledgeService(),
                new FakeLocalGraphKnowledgeService(),
                observabilityService
        );

        RAGTraceContext.setTraceId("trace-cache-prime");
        try {
            coordinator.retrieve("什么是缓存雪崩", "", KnowledgeRetrievalMode.RAG_ONLY);
        } finally {
            RAGTraceContext.clear();
        }

        RAGTraceContext.setTraceId("trace-cache-hit");
        try {
            coordinator.retrieve("什么是缓存雪崩", "", KnowledgeRetrievalMode.RAG_ONLY);
            RAGObservabilityService.RAGTrace trace = observabilityService.getTraceDetail("trace-cache-hit");
            assertNotNull(trace);
            RAGObservabilityService.RAGTraceNode node = trace.nodes().stream()
                    .filter(item -> "RETRIEVAL".equals(item.nodeType()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(node);
            assertEquals("RETRIEVAL_CACHE_HIT", node.nodeName());
            assertEquals(5, node.metrics().retrievedDocs());
            assertNotNull(node.details());
            assertEquals(Boolean.TRUE, node.details().cacheHit());
            assertEquals(2, node.details().retrievedDocumentRefs().size());
        } finally {
            RAGTraceContext.clear();
        }
    }

    private static final class FakeRagKnowledgeService extends RagKnowledgeService {

        FakeRagKnowledgeService() {
            super(null);
        }

        @Override
        public KnowledgeContextPacket retrieve(String question,
                                              String userAnswer,
                                              KnowledgeRetrievalMode requestedMode,
                                              KnowledgeRetrievalMode resolvedMode,
                                              String fallbackReason) {
            return new KnowledgeContextPacket(
                    requestedMode,
                    resolvedMode,
                    false,
                    true,
                    fallbackReason,
                    "redis 持久化 why fast",
                    "rag context",
                    "[图1] Redis 架构图 - 来源: redis-arch.png",
                    "1. [obsidian:redis.md] Redis缓存\n2. [obsidian:java.md] Java集合",
                    List.of(new ImageService.ImageResult(
                            "img-2",
                            "redis-arch.png",
                            "/api/images/img-2/file",
                            "/api/images/img-2/thumbnail?maxWidth=400",
                            "Redis 架构图",
                            null,
                            0.91d,
                            "text_associated"
                    )),
                    false,
                    5,
                    List.of("redis.md | Redis缓存", "java.md | Java集合")
            );
        }
    }

    private static final class FakeLocalGraphKnowledgeService extends LocalGraphKnowledgeService {

        private final boolean alwaysFail;
        private final LocalGraphFailureReason failureReason;

        FakeLocalGraphKnowledgeService() {
            this(false, null);
        }

        FakeLocalGraphKnowledgeService(boolean alwaysFail, LocalGraphFailureReason failureReason) {
            super(
                    new KnowledgeRetrievalProperties(),
                    null,
                    null,
                    null,
                    null,
                    new RAGObservabilityService(new ObservabilitySwitchProperties()),
                    null
            );
            this.alwaysFail = alwaysFail;
            this.failureReason = failureReason;
        }

        @Override
        public KnowledgeContextPacket retrieve(String question, KnowledgeRetrievalMode requestedMode) {
            if (alwaysFail) {
                throw new LocalGraphRetrievalException(failureReason, "failed");
            }
            return new KnowledgeContextPacket(
                    requestedMode,
                    requestedMode,
                    true,
                    false,
                    "",
                    question,
                    "local context",
                    "[图1] Redis 持久化流程图 - 来源: redis.png",
                    "1. [local_graph:Redis/AOF] Redis AOF\n2. [local_graph_backlink:Redis/RDB] Redis RDB",
                    List.of(new ImageService.ImageResult(
                            "img-1",
                            "redis.png",
                            "/api/images/img-1/file",
                            "/api/images/img-1/thumbnail?maxWidth=400",
                            "Redis 持久化流程图",
                            null,
                            0.88d,
                            "local_graph_associated"
                    )),
                    false,
                    3,
                    List.of("[primary] Redis/AOF", "[backlink] Redis/RDB")
            );
        }
    }
}
