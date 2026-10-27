package io.github.imzmq.interview.knowledge.application;

import io.github.imzmq.interview.observability.core.RAGTraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RAGServiceTopKTest {

    @AfterEach
    void clearTraceContext() {
        RAGTraceContext.clear();
    }

    @Test
    void keepsOnlineCompatibilityAtDefaultTopFive() {
        CapturingKnowledgePacketBuilder builder = new CapturingKnowledgePacketBuilder();
        RAGService service = serviceWith(builder);
        RAGTraceContext.pushNode("existing-parent");

        service.buildKnowledgePacket("question", "answer", false);

        assertThat(builder.topK).isEqualTo(5);
        assertThat(builder.allowWebFallback).isFalse();
    }

    @Test
    void forwardsEvaluationTopKAndBoundsUnsafeValues() {
        CapturingKnowledgePacketBuilder builder = new CapturingKnowledgePacketBuilder();
        RAGService service = serviceWith(builder);
        RAGTraceContext.pushNode("existing-parent");

        service.buildKnowledgePacket("question", "", false, 20);
        assertThat(builder.topK).isEqualTo(20);

        service.buildKnowledgePacket("question", "", false, 0);
        assertThat(builder.topK).isEqualTo(1);

        service.buildKnowledgePacket("question", "", false, 101);
        assertThat(builder.topK).isEqualTo(100);
    }

    private RAGService serviceWith(KnowledgePacketBuilder builder) {
        return new RAGService(null, null, null, null, null, builder, null);
    }

    private static final class CapturingKnowledgePacketBuilder extends KnowledgePacketBuilder {
        private int topK = -1;
        private boolean allowWebFallback = true;

        private CapturingKnowledgePacketBuilder() {
            super(null, null, null, null, null, null, null, null, null, null);
        }

        @Override
        public RAGService.KnowledgePacket build(
                String question,
                String userAnswer,
                boolean allowWebFallback,
                int topK
        ) {
            this.allowWebFallback = allowWebFallback;
            this.topK = topK;
            return new RAGService.KnowledgePacket(question, null, "", "[]", false);
        }
    }
}
