package com.example.interview.service;

import com.example.interview.config.KnowledgeRetrievalProperties;
import com.example.interview.config.ObservabilitySwitchProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
                    question,
                    "context",
                    "",
                    "evidence",
                    List.of(),
                    false
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
                    new RAGObservabilityService(new ObservabilitySwitchProperties())
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
                    "",
                    "local evidence",
                    List.of(),
                    false
            );
        }
    }
}
