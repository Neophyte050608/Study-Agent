package com.example.interview.service;

import com.example.interview.config.ObservabilitySwitchProperties;
import com.example.interview.config.ParentChildRetrievalProperties;
import com.example.interview.config.RagRetrievalProperties;
import com.example.interview.entity.RagParentDO;
import com.example.interview.modelrouting.ModelRouteType;
import com.example.interview.modelrouting.RoutingChatService;
import com.example.interview.tool.WebSearchTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
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
@MockitoSettings(strictness = Strictness.LENIENT)
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
    private RetrievalTokenizerService retrievalTokenizerService;
    private RagRetrievalProperties ragRetrievalProperties;

    @BeforeEach
    void setUp() {
        ParentChildRetrievalProperties parentChildRetrievalProperties = new ParentChildRetrievalProperties();
        parentChildRetrievalProperties.setEnabled(true);
        parentChildRetrievalProperties.setHydrateParentTopN(8);
        parentChildRetrievalProperties.setHydrateParentContextChars(120);
        parentChildRetrievalProperties.setHydrateChildMatchChars(80);
        retrievalTokenizerService = new RetrievalTokenizerService();
        ragRetrievalProperties = new RagRetrievalProperties();
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
                retrievalTokenizerService,
                ragRetrievalProperties,
                parentChildRetrievalProperties,
                parentChildIndexService
        );
    }

    /**
     * 验证 Parent-Child 回填会同时保留父文上下文与 child 命中片段。
     */
    @Test
    void shouldHydrateParentContextAndChildSnippetWhenChildMatched() {
        AtomicInteger callIndex = new AtomicInteger(0);
        when(routingChatService.callWithFirstPacketProbeSupplier(any(), anyString(), any(ModelRouteType.class), anyString()))
                .thenAnswer(invocation -> callIndex.getAndIncrement() == 0 ? "缓存一致性 检索词" : "{\"score\":80,\"accuracy\":80,\"logic\":80,\"depth\":80,\"boundary\":80,\"deductions\":[],\"citations\":[\"1. [ok]\"],\"conflicts\":[],\"feedback\":\"ok\",\"nextQuestion\":\"继续\"}");
        when(agentSkillService.resolveSkillBlock(any())).thenReturn("");
        when(lexicalIndexService.searchIntentDirected(anyString(), any(), anyInt())).thenReturn(List.of());
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
        parent.setParentText("parent text from parent-child index with child content and broader explanation");
        when(parentChildIndexService.queryParentsByIds(any())).thenReturn(Map.of("p-001", parent));

        RAGService.KnowledgePacket packet = ragService.buildKnowledgePacket("如何保证缓存一致性", "通过延迟双删");
        assertTrue(packet.context().contains("\u3010\u7AE0\u8282\u8DEF\u5F84\u3011"));
        assertTrue(packet.context().contains("\u3010\u7236\u6587\u4E0A\u4E0B\u6587\u3011"));
        assertTrue(packet.context().contains("\u3010\u547D\u4E2D\u7247\u6BB5\u3011child content"));
        assertTrue(packet.context().contains("parent text from parent-child index"));
        assertTrue(packet.retrievalEvidence().contains("parent=p-001"));
        assertTrue(packet.retrievalEvidence().contains("child=0"));
        assertTrue(packet.retrievalEvidence().contains("\u547D\u4E2D=child content"));
        assertTrue(packet.retrievalEvidence().contains("\u4E0A\u6587="));
    }
}
