package com.example.interview.service;

import com.example.interview.config.ObservabilitySwitchProperties;
import com.example.interview.config.ParentChildRetrievalProperties;
import com.example.interview.entity.RagParentDO;
import com.example.interview.modelrouting.ModelRouteType;
import com.example.interview.modelrouting.RoutingChatService;
import com.example.interview.tool.WebSearchTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ParentChildRetrievalHydrationTest {

    @Mock
    private RoutingChatService routingChatService;

    @Mock
    private VectorStore vectorStore;

    @Mock
    private WebSearchTool webSearchTool;

    @Mock
    private LexicalIndexService lexicalIndexService;

    @Mock
    private RAGObservabilityService observabilityService;

    @Mock
    private AgentSkillService agentSkillService;

    @Mock
    private PromptTemplateService promptTemplateService;

    @Mock
    private PromptManager promptManager;

    @Mock
    private com.example.interview.graph.TechConceptRepository techConceptRepository;

    @Mock
    private ObservabilitySwitchProperties observabilitySwitchProperties;

    @Mock
    private ParentChildIndexService parentChildIndexService;

    private RAGService ragService;

    @BeforeEach
    void setUp() {
        ParentChildRetrievalProperties parentChildRetrievalProperties = new ParentChildRetrievalProperties();
        parentChildRetrievalProperties.setEnabled(true);
        parentChildRetrievalProperties.setHydrateParentTopN(8);
        Executor executor = Runnable::run;
        ragService = new RAGService(
                routingChatService,
                vectorStore,
                lexicalIndexService,
                webSearchTool,
                observabilityService,
                agentSkillService,
                promptTemplateService,
                promptManager,
                executor,
                techConceptRepository,
                observabilitySwitchProperties,
                parentChildRetrievalProperties,
                parentChildIndexService
        );
    }

    @Test
    void shouldHydrateParentTextWhenChildMatched() {
        AtomicInteger callIndex = new AtomicInteger(0);
        when(routingChatService.callWithFirstPacketProbeSupplier(any(), anyString(), any(ModelRouteType.class), anyString()))
                .thenAnswer(invocation -> callIndex.getAndIncrement() == 0 ? "缓存一致性 检索词" : "{\"score\":80,\"accuracy\":80,\"logic\":80,\"depth\":80,\"boundary\":80,\"deductions\":[],\"citations\":[\"1. [ok]\"],\"conflicts\":[],\"feedback\":\"ok\",\"nextQuestion\":\"继续\"}");
        when(agentSkillService.resolveSkillBlock(any())).thenReturn("");
        when(lexicalIndexService.searchIntentDirected(anyString(), any(), anyInt())).thenReturn(List.of());
        when(techConceptRepository.findRelatedConceptsWithinTwoHops(anyString())).thenReturn(List.of());
        when(observabilitySwitchProperties.isRagTraceEnabled()).thenReturn(false);

        Document childDoc = new Document("child content");
        childDoc.getMetadata().put("file_path", "note/cache.md");
        childDoc.getMetadata().put("source_type", "obsidian");
        childDoc.getMetadata().put("parent_id", "p-001");
        childDoc.getMetadata().put("child_index", 0);
        childDoc.getMetadata().put("knowledge_tags", "技术栈");
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of(childDoc));

        RagParentDO parent = new RagParentDO();
        parent.setParentId("p-001");
        parent.setFilePath("note/cache.md");
        parent.setSectionPath("缓存 > 一致性");
        parent.setSourceType("obsidian");
        parent.setKnowledgeTags("技术栈");
        parent.setParentText("parent text from parent-child index");
        when(parentChildIndexService.queryParentsByIds(any())).thenReturn(Map.of("p-001", parent));

        RAGService.KnowledgePacket packet = ragService.buildKnowledgePacket("如何保证缓存一致性", "通过延迟双删");
        assertTrue(packet.context().contains("parent text from parent-child index"));
        assertTrue(packet.retrievalEvidence().contains("parent=p-001"));
        assertTrue(packet.retrievalEvidence().contains("child=0"));
    }
}
