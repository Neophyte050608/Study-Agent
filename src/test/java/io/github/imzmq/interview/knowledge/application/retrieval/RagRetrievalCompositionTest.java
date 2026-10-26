package io.github.imzmq.interview.knowledge.application.retrieval;

import io.github.imzmq.interview.agent.application.contract.AgentSkillInstructions;
import io.github.imzmq.interview.chat.application.contract.PromptRenderer;
import io.github.imzmq.interview.config.knowledge.ParentChildRetrievalProperties;
import io.github.imzmq.interview.config.observability.ObservabilitySwitchProperties;
import io.github.imzmq.interview.knowledge.application.KnowledgePacketBuilder;
import io.github.imzmq.interview.knowledge.application.RAGService;
import io.github.imzmq.interview.knowledge.application.indexing.LexicalIndexService;
import io.github.imzmq.interview.knowledge.application.indexing.ParentChildIndexService;
import io.github.imzmq.interview.knowledge.application.indexing.RetrievalTokenizerService;
import io.github.imzmq.interview.knowledge.infrastructure.graph.TechConceptRepository;
import io.github.imzmq.interview.modelrouting.application.contract.structuredoutput.StructuredOutputParser;
import io.github.imzmq.interview.modelrouting.core.RoutingChatService;
import io.github.imzmq.interview.observability.application.contract.trace.RagTraceOperations;
import io.github.imzmq.interview.observability.application.contract.trace.TraceNodeDetails;
import io.github.imzmq.interview.observability.application.contract.trace.TraceNodeMetrics;
import io.github.imzmq.interview.observability.core.RAGTraceContext;
import io.github.imzmq.interview.skill.runtime.SkillOrchestrator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Verifies the real constructor graph without a model, database or media bean. */
class RagRetrievalCompositionTest {
    private final RagTraceOperations trace = mock(RagTraceOperations.class);
    private final QueryRewriteService rewrite = mock(QueryRewriteService.class);
    private final EvidenceEvaluationService evaluation = mock(EvidenceEvaluationService.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(RAGService.class)
            .withBean(KnowledgePacketBuilder.class)
            .withBean(HybridEvidenceRetriever.class)
            .withBean(EvidenceFusionService.class)
            .withBean(GroundingContextAssembler.class)
            .withBean(VectorStore.class, () -> mock(VectorStore.class))
            .withBean(LexicalIndexService.class, () -> mock(LexicalIndexService.class))
            .withBean(TechConceptRepository.class, () -> mock(TechConceptRepository.class))
            .withBean(ParentChildIndexService.class, () -> mock(ParentChildIndexService.class))
            .withBean(ParentChildRetrievalProperties.class)
            .withBean(RetrievalTokenizerService.class)
            .withBean("ragRetrieveExecutor", Executor.class, () -> Runnable::run)
            .withBean(RagTraceOperations.class, () -> trace)
            .withBean(QueryRewriteService.class, () -> rewrite)
            .withBean(EvidenceEvaluationService.class, () -> evaluation)
            .withBean(WebFallbackService.class, () -> mock(WebFallbackService.class))
            .withBean(SkillOrchestrator.class, () -> mock(SkillOrchestrator.class))
            .withBean(ObservabilitySwitchProperties.class)
            .withBean(RoutingChatService.class, () -> mock(RoutingChatService.class))
            .withBean(AgentSkillInstructions.class, () -> mock(AgentSkillInstructions.class))
            .withBean(PromptRenderer.class, () -> mock(PromptRenderer.class))
            .withBean(StructuredOutputParser.class, () -> mock(StructuredOutputParser.class));

    @AfterEach
    void clearTrace() {
        RAGTraceContext.clear();
    }

    @Test
    void wiresRealRetrievalGraphAndPreservesRootChildTraceWithoutOptionalMedia() {
        RAGTraceContext.clear();
        RAGTraceContext.setTraceId("trace-test");
        when(rewrite.buildRewrittenQuery(eq("redis"), eq(""), any())).thenReturn(RewrittenQuery.fallback("redis"));
        when(evaluation.decide(any(), anyList(), anyString(), anyDouble(), eq(false), anyString(), any()))
                .thenReturn(new EvidenceEvaluationService.EvidenceDecision(false, "DISABLED"));
        // The trace provider owns node stack mutation; emulate its documented runtime contract.
        doAnswer(invocation -> { RAGTraceContext.pushNode(invocation.getArgument(1)); return null; })
                .when(trace).startNode(anyString(), anyString(), nullable(String.class), anyString(), anyString());
        doAnswer(invocation -> { RAGTraceContext.removeNode(invocation.getArgument(1)); return null; })
                .when(trace).endNode(anyString(), anyString(), anyString(), nullable(String.class), nullable(String.class));
        doAnswer(invocation -> { RAGTraceContext.removeNode(invocation.getArgument(1)); return null; })
                .when(trace).endNode(anyString(), anyString(), anyString(), anyString(), nullable(String.class),
                        nullable(TraceNodeMetrics.class), nullable(TraceNodeDetails.class));

        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(RAGService.class).hasSingleBean(HybridEvidenceRetriever.class);
            RAGService.KnowledgePacket packet = context.getBean(RAGService.class).buildKnowledgePacket("redis", "", false);
            assertThat(packet.context()).isEmpty();
            assertThat(packet.retrievedImages()).isEmpty();
            assertThat(packet.retrievalEvidence()).isEqualTo("[]");
            assertThat(packet.webFallbackUsed()).isFalse();
        });

        ArgumentCaptor<String> ids = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> parents = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> types = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
        verify(trace, times(5)).startNode(eq("trace-test"), ids.capture(), parents.capture(), types.capture(), names.capture());
        assertThat(types.getAllValues()).containsExactly("KNOWLEDGE_PACKET", "RETRIEVAL", "RETRIEVAL", "RETRIEVAL", "RETRIEVAL");
        assertThat(names.getAllValues()).containsExactly("Knowledge Packet Build", "QUERY_REWRITE", "DOC_RETRIEVE", "IMAGE_ASSOC_RETRIEVE", "IMAGE_SEMANTIC_RETRIEVE");
        assertThat(parents.getAllValues()).containsExactly(null, ids.getAllValues().getFirst(), ids.getAllValues().getFirst(),
                ids.getAllValues().getFirst(), ids.getAllValues().getFirst());
        assertThat(RAGTraceContext.getCurrentNodeId()).isNull();
    }

    @Test
    void reusesExistingTraceParentWithoutStartingAnotherRoot() {
        RAGTraceContext.clear();
        RAGTraceContext.setTraceId("trace-existing");
        RAGTraceContext.pushNode("external-parent");
        when(rewrite.buildRewrittenQuery(anyString(), anyString(), any())).thenReturn(RewrittenQuery.fallback("redis"));
        when(evaluation.decide(any(), anyList(), anyString(), anyDouble(), eq(false), anyString(), any()))
                .thenReturn(new EvidenceEvaluationService.EvidenceDecision(false, "DISABLED"));
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            context.getBean(RAGService.class).buildKnowledgePacket("redis", "", false);
        });
        verify(trace, never()).startNode(anyString(), anyString(), isNull(), eq("KNOWLEDGE_PACKET"), anyString());
        verify(trace, times(4)).startNode(eq("trace-existing"), anyString(), eq("external-parent"), anyString(), anyString());
        assertThat(RAGTraceContext.getCurrentNodeId()).isEqualTo("external-parent");
    }
}
