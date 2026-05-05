package io.github.imzmq.interview.service;

import io.github.imzmq.interview.config.observability.ObservabilitySwitchProperties;
import io.github.imzmq.interview.config.knowledge.ParentChildRetrievalProperties;
import io.github.imzmq.interview.config.knowledge.RagRetrievalProperties;
import io.github.imzmq.interview.config.skill.SkillExecutionProperties;
import io.github.imzmq.interview.interview.domain.Question;
import io.github.imzmq.interview.graph.domain.TechConceptRepository;
import io.github.imzmq.interview.graph.domain.TechConceptSnippetView;
import io.github.imzmq.interview.knowledge.application.indexing.LexicalIndexService;
import io.github.imzmq.interview.knowledge.application.indexing.ParentChildIndexService;
import io.github.imzmq.interview.knowledge.application.observability.RAGObservabilityService;
import io.github.imzmq.interview.knowledge.application.RAGService;
import io.github.imzmq.interview.knowledge.application.indexing.RetrievalTokenizerService;
import io.github.imzmq.interview.agent.application.AgentSkillService;
import io.github.imzmq.interview.chat.application.PromptManager;
import io.github.imzmq.interview.chat.application.PromptTemplateService;
import io.github.imzmq.interview.media.application.ImageService;
import io.github.imzmq.interview.modelrouting.core.ModelRouteType;
import io.github.imzmq.interview.modelrouting.core.RoutingChatService;
import io.github.imzmq.interview.modelrouting.core.TimeoutHint;
import io.github.imzmq.interview.mcp.application.McpGatewayService;
import io.github.imzmq.interview.skill.builtin.EvidenceEvaluatorSkill;
import io.github.imzmq.interview.skill.builtin.CodingInterviewCoachSkill;
import io.github.imzmq.interview.skill.builtin.InterviewReportGeneratorSkill;
import io.github.imzmq.interview.skill.builtin.PersonalizedLearningPlannerSkill;
import io.github.imzmq.interview.skill.builtin.QueryOptimizerSkill;
import io.github.imzmq.interview.skill.builtin.QuestionStrategySkill;
import io.github.imzmq.interview.skill.runtime.SkillExecutor;
import io.github.imzmq.interview.chat.application.LlmJsonParser;
import io.github.imzmq.interview.skill.client.SkillMcpClient;
import io.github.imzmq.interview.skill.runtime.SkillOrchestrator;
import io.github.imzmq.interview.skill.runtime.SkillRegistry;
import io.github.imzmq.interview.tool.search.WebSearchTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RAGServiceTest {

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
    private TechConceptRepository techConceptRepository;

    @Mock
    private ObservabilitySwitchProperties observabilitySwitchProperties;

    @Mock
    private ParentChildRetrievalProperties parentChildRetrievalProperties;

    @Mock
    private ParentChildIndexService parentChildIndexService;

    @Mock
    private ImageService imageService;

    @Mock
    private McpGatewayService mcpGatewayService;

    private final Executor ragRetrieveExecutor = Runnable::run;

    private RetrievalTokenizerService retrievalTokenizerService;
    private RagRetrievalProperties ragRetrievalProperties;
    private RAGService ragService;

    @BeforeEach
    void setUp() {
        retrievalTokenizerService = new RetrievalTokenizerService();
        ragRetrievalProperties = new RagRetrievalProperties();
        SkillExecutionProperties skillExecutionProperties = new SkillExecutionProperties();
        SkillRegistry skillRegistry = new SkillRegistry(List.of(
                new QueryOptimizerSkill(routingChatService, agentSkillService),
                new EvidenceEvaluatorSkill(ragRetrievalProperties),
                new QuestionStrategySkill(),
                new CodingInterviewCoachSkill(),
                new InterviewReportGeneratorSkill(),
                new PersonalizedLearningPlannerSkill()
        ));
        SkillOrchestrator skillOrchestrator = new SkillOrchestrator(
                skillRegistry,
                new SkillExecutor(skillExecutionProperties),
                skillExecutionProperties
        );
        SkillMcpClient skillMcpClient = new SkillMcpClient(mcpGatewayService);
        LlmJsonParser llmJsonParser = new LlmJsonParser(new ObjectMapper());
        ragService = new RAGService(
                routingChatService,
                vectorStore,
                lexicalIndexService,
                webSearchTool,
                observabilityService,
                agentSkillService,
                promptTemplateService,
                promptManager,
                ragRetrieveExecutor,
                techConceptRepository,
                observabilitySwitchProperties,
                retrievalTokenizerService,
                ragRetrievalProperties,
                parentChildRetrievalProperties,
                parentChildIndexService,
                imageService,
                skillOrchestrator,
                skillMcpClient,
                llmJsonParser
        );
        when(promptManager.renderSplit(anyString(), anyString(), anyMap()))
                .thenReturn(new PromptManager.PromptPair("system", "user"));
        when(routingChatService.callWithMetadata(anyString(), anyString(), any(ModelRouteType.class), anyString()))
                .thenReturn(new RoutingChatService.RoutingResult("{\"score\":80,\"accuracy\":80,\"logic\":80,\"depth\":80,\"boundary\":80,\"deductions\":[],\"citations\":[\"1. [ok]\"],\"conflicts\":[],\"feedback\":\"ok\",\"nextQuestion\":\"继续\"}", 0, 0, 0L));
    }

    @Test
    void shouldCallWebSearchWhenVectorStoreIsEmpty() {
        when(agentSkillService.resolveSkillBlock("query-optimizer")).thenReturn("");
        when(routingChatService.callWithFirstPacketProbeSupplier(any(Supplier.class), nullable(String.class), any(ModelRouteType.class), any(TimeoutHint.class), anyString()))
                .thenReturn("Java Concurrency");
        when(observabilitySwitchProperties.isRagTraceEnabled()).thenReturn(false);
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of());
        when(mcpGatewayService.invoke(anyString(), anyString(), anyMap(), anyMap())).thenReturn(Map.of(
                "status", "fallback_stub",
                "result", Map.of()
        ));
        when(webSearchTool.run(any())).thenReturn(List.of("Web info about concurrency"));

        ragService.buildKnowledgePacket("什么是并发", "同时执行");

        verify(webSearchTool, times(1)).run(any(WebSearchTool.Query.class));
    }

    @Test
    void shouldCallWebSearchWhenLocalRetrievalQualityIsLow() {
        ragRetrievalProperties.setWebFallbackMode(RagRetrievalProperties.WebFallbackMode.ON_LOW_QUALITY);
        ragRetrievalProperties.setWebFallbackQualityThreshold(0.40D);

        when(agentSkillService.resolveSkillBlock("query-optimizer")).thenReturn("");
        when(routingChatService.callWithFirstPacketProbeSupplier(any(Supplier.class), nullable(String.class), any(ModelRouteType.class), any(TimeoutHint.class), anyString()))
                .thenReturn("Redis 为什么快");
        when(observabilitySwitchProperties.isRagTraceEnabled()).thenReturn(false);
        when(lexicalIndexService.searchIntentDirected(anyString(), any(), anyInt())).thenReturn(List.of());

        Document unrelatedDoc = new Document("Docker 镜像分层机制与构建缓存策略");
        unrelatedDoc.getMetadata().put("file_path", "note/docker.md");
        unrelatedDoc.getMetadata().put("source_type", "obsidian");
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of(unrelatedDoc));
        when(mcpGatewayService.invoke(anyString(), anyString(), anyMap(), anyMap())).thenReturn(Map.of(
                "status", "fallback_stub",
                "result", Map.of()
        ));
        when(webSearchTool.run(any())).thenReturn(List.of("Redis 基于内存访问与高效数据结构实现低延迟"));

        RAGService.KnowledgePacket packet = ragService.buildKnowledgePacket("Redis 为什么快", "");

        assertTrue(packet.webFallbackUsed());
        verify(webSearchTool, times(1)).run(any(WebSearchTool.Query.class));
    }

    /**
     * 验证 GraphRAG 通道会优先输出描述性文本，而不是只有概念名。
     */
    @Test
    void shouldBuildGraphRagContextFromConceptDescriptions() {
        when(agentSkillService.resolveSkillBlock("query-optimizer")).thenReturn("");
        when(routingChatService.callWithFirstPacketProbeSupplier(any(Supplier.class), nullable(String.class), any(ModelRouteType.class), any(TimeoutHint.class), anyString()))
                .thenReturn("HashMap 树化");
        when(observabilitySwitchProperties.isRagTraceEnabled()).thenReturn(false);
        when(lexicalIndexService.tokenize(anyString())).thenReturn(List.of("HashMap"));
        when(lexicalIndexService.searchIntentDirected(anyString(), any(), anyInt())).thenReturn(List.of());
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of());
        when(mcpGatewayService.invoke(anyString(), anyString(), anyMap(), anyMap())).thenReturn(Map.of(
                "status", "blocked",
                "result", Map.of()
        ));
        when(techConceptRepository.findRelatedConceptSnippetsBatch(any())).thenReturn(List.of(
                batchGraphConcept("HashMap", "红黑树", "JDK 8 中链表过长时会树化，避免哈希桶退化。", "Algorithm"),
                batchGraphConcept("HashMap", "扩容", "容量不足时会触发 rehash，并影响元素迁移成本。", "Concept")
        ));

        RAGService.KnowledgePacket packet = ragService.buildKnowledgePacket("HashMap 为什么会树化", "");

        assertFalse(packet.webFallbackUsed());
        assertTrue(packet.context().contains("红黑树（Algorithm）"));
        assertTrue(packet.context().contains("链表过长时会树化"));
        assertTrue(packet.context().contains("扩容（Concept）"));
        assertTrue(packet.retrievalEvidence().contains("graph_rag"));
    }

    @Test
    void shouldFilterInvalidEvidenceReferences() throws Exception {
        when(routingChatService.callWithFirstPacketProbeSupplier(any(Supplier.class), nullable(String.class), any(ModelRouteType.class), any(TimeoutHint.class), anyString()))
                .thenReturn(
                        "Spring Transaction"
                );
        when(promptManager.renderSplit(eq("interviewer"), eq("evaluation"), anyMap()))
                .thenReturn(new PromptManager.PromptPair("system", "user"));
        when(routingChatService.callWithMetadata(anyString(), anyString(), any(ModelRouteType.class), anyString()))
                .thenReturn(new RoutingChatService.RoutingResult("""
                        {"score":85,"accuracy":84,"logic":83,"depth":82,"boundary":81,
                        "deductions":["边界解释不足"],
                        "citations":["99. [fake]","1. [ok]"],
                        "conflicts":["异常结论~7","隔离级别描述不全~1"],
                        "feedback":"回答较好","nextQuestion":"说一下面试中的事务隔离级别"}
                        """, 0, 0, 0L));

        Document vectorDoc = new Document("事务的隔离级别包括读未提交、读已提交、可重复读和串行化。");
        vectorDoc.getMetadata().put("file_path", "note/tx.md");
        vectorDoc.getMetadata().put("knowledge_tags", "技术栈");
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of(vectorDoc));
        when(observabilitySwitchProperties.isRagTraceEnabled()).thenReturn(false);

        String result = ragService.processAnswer("Spring", "什么是事务隔离级别", "回答", "INTERMEDIATE", "PROBE", 72.0, "画像");
        JsonNode node = new ObjectMapper().readTree(result);

        assertEquals(1, node.path("citations").size());
        assertEquals("1. [ok]", node.path("citations").get(0).asText());
        assertEquals(1, node.path("conflicts").size());
        assertEquals("隔离级别描述不全~1", node.path("conflicts").get(0).asText());
        assertTrue(node.path("deductions").toString().contains("自动过滤"));
    }

    @Test
    void shouldInjectKnowledgeSkillIntoRewritePrompt() {
        when(agentSkillService.resolveSkillBlock("query-optimizer")).thenReturn("### Skill: query-optimizer\n遵循混合检索流程");
        when(routingChatService.callWithFirstPacketProbeSupplier(any(Supplier.class), nullable(String.class), any(ModelRouteType.class), any(TimeoutHint.class), anyString()))
                .thenReturn("事务");
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of());
        when(mcpGatewayService.invoke(anyString(), anyString(), anyMap(), anyMap())).thenReturn(Map.of(
                "status", "blocked",
                "result", Map.of()
        ));
        when(webSearchTool.run(any())).thenReturn(List.of("fallback"));
        when(observabilitySwitchProperties.isRagTraceEnabled()).thenReturn(false);

        ragService.buildKnowledgePacket("什么是事务", "用于一致性");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(routingChatService).callWithFirstPacketProbeSupplier(any(Supplier.class), captor.capture(), any(ModelRouteType.class), any(TimeoutHint.class), anyString());
        assertTrue(captor.getValue().contains("query-optimizer"));
    }

    @Test
    void shouldPrioritizeInterviewExperienceInEvidenceOrder() {
        when(agentSkillService.resolveSkillBlock("query-optimizer")).thenReturn("");
        when(routingChatService.callWithFirstPacketProbeSupplier(any(Supplier.class), nullable(String.class), any(ModelRouteType.class), any(TimeoutHint.class), anyString()))
                .thenReturn(
                        "缓存 一致性",
                        """
                        {"score":80,"accuracy":80,"logic":80,"depth":80,"boundary":80,
                        "deductions":[],"citations":["1. [ok]"],"conflicts":[],
                        "feedback":"回答可接受","nextQuestion":"继续"}
                        """
                );
        when(promptManager.render(eq("evaluation"), anyMap())).thenReturn("evaluation-prompt");
        when(observabilitySwitchProperties.isRagTraceEnabled()).thenReturn(false);

        Document obsidianDoc = new Document("缓存一致性常见方案包括延迟双删和消息队列。");
        obsidianDoc.getMetadata().put("file_path", "note/cache.md");
        obsidianDoc.getMetadata().put("knowledge_tags", "技术栈");
        obsidianDoc.getMetadata().put("source_type", "obsidian");

        Document interviewDoc = new Document("面经高频追问：双写一致性失败后如何补偿。");
        interviewDoc.getMetadata().put("file_path", "interview/tencent.md");
        interviewDoc.getMetadata().put("knowledge_tags", "八股");
        interviewDoc.getMetadata().put("source_type", "interview_experience");

        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of(obsidianDoc, interviewDoc));
        when(mcpGatewayService.invoke(anyString(), anyString(), anyMap(), anyMap())).thenReturn(Map.of(
                "status", "blocked",
                "result", Map.of()
        ));

        ragService.processAnswer("缓存", "如何保证缓存一致性", "回答", "INTERMEDIATE", "PROBE", 70.0, "画像");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(promptManager, atLeastOnce()).renderSplit(eq("interviewer"), eq("evaluation"), captor.capture());
        Object evidence = captor.getValue().get("retrievalEvidence");
        assertTrue(String.valueOf(evidence).contains("1. [interview_experience:interview/tencent.md]"));
    }

    @Test
    void shouldExposeSanitizedUpstreamBodyInErrorSummary() {
        HttpServerErrorException exception = HttpServerErrorException.create(
                HttpStatus.BAD_GATEWAY,
                "bad gateway",
                HttpHeaders.EMPTY,
                "{\"error\":\"invalid api key\",\"api_key\":\"sk-very-secret-token-1234567890\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8
        );

        String summary = ragService.summarizeError(exception);

        assertTrue(summary.contains("status=502"));
        assertTrue(summary.contains("api_key"));
        assertTrue(summary.contains("***"));
        assertFalse(summary.contains("sk-very-secret-token-1234567890"));
    }

    @Test
    void shouldRedactLongTokenInMessageSummary() {
        RuntimeException exception = new RuntimeException("upstream failure authorization=Bearer abcdefghijklmnopqrstuvwxyz0123456789");

        String summary = ragService.summarizeError(exception);

        assertTrue(summary.contains("***"));
        assertFalse(summary.contains("abcdefghijklmnopqrstuvwxyz0123456789"));
    }

    @Test
    void shouldReturnFallbackFirstQuestionWhenChatModelTimeout() {
        when(promptManager.renderSplit(eq("interviewer"), eq("first-question"), anyMap()))
                .thenReturn(new PromptManager.PromptPair("system", "user"));
        when(routingChatService.callWithFirstPacketProbeSupplier(any(Supplier.class), anyString(), anyString(), any(ModelRouteType.class), any(TimeoutHint.class), anyString()))
                .thenReturn("你好！欢迎参加今天的 JVM 模拟面试。在正式开始技术交流之前，能请你先花 1-2 分钟做一个简单的自我介绍吗？");

        String firstQuestion = ragService.generateFirstQuestion("", "JVM", "画像", false, List.of());

        assertTrue(firstQuestion.contains("JVM"));
        assertTrue(firstQuestion.contains("自我介绍"));
    }

    @Test
    void shouldReturnFallbackEvaluationWhenLayeredEvaluateTimeout() throws Exception {
        when(agentSkillService.resolveSkillBlock("query-optimizer")).thenReturn("");
        when(routingChatService.callWithFirstPacketProbeSupplier(any(Supplier.class), nullable(String.class), any(ModelRouteType.class), any(TimeoutHint.class), anyString()))
                .thenReturn("JVM 内存模型");
        when(promptManager.renderSplit(eq("interviewer"), eq("evaluation"), anyMap()))
                .thenReturn(new PromptManager.PromptPair("system", "user"));
        when(routingChatService.callWithMetadata(anyString(), anyString(), any(ModelRouteType.class), anyString()))
                .thenThrow(new ResourceAccessException("timeout"));
        when(mcpGatewayService.invoke(anyString(), anyString(), anyMap(), anyMap())).thenReturn(Map.of(
                "status", "blocked",
                "result", Map.of()
        ));

        RAGService.EvaluationResult result = ragService.evaluateWithKnowledge(
                "Java",
                "什么是 JVM 内存模型",
                "我的回答",
                "INTERMEDIATE",
                "PROBE",
                70.0,
                "画像",
                "",
                new RAGService.KnowledgePacket("query", List.of(), "", "[]", true)
        );

        JsonNode node = new ObjectMapper().readTree(result.json());
        assertEquals(0, node.path("score").asInt());
        assertTrue(node.path("feedback").asText().contains("超时") || node.path("feedback").asText().contains("不可用"));
    }

    @Test
    void shouldNormalizeVerboseFirstQuestionOutput() {
        when(promptManager.renderSplit(eq("interviewer"), eq("first-question"), anyMap()))
                .thenReturn(new PromptManager.PromptPair("system", "user"));
        when(routingChatService.callWithFirstPacketProbeSupplier(any(Supplier.class), anyString(), anyString(), any(ModelRouteType.class), any(TimeoutHint.class), anyString()))
                .thenReturn("""
                        **第一题：请简述 Java 中 HashMap 的实现原理？**

                        **出题依据（策略提示）：**
                        - 难度适配
                        - 学习画像关联
                        """);

        String question = ragService.generateFirstQuestion("", "Java", "画像", false, List.of());

        assertTrue(question.contains("HashMap"));
        assertFalse(question.contains("出题依据"));
        assertFalse(question.contains("策略提示"));
    }

    @Test
    void shouldInjectExecutableQuestionStrategyIntoFirstQuestionPrompt() {
        when(promptManager.renderSplit(eq("interviewer"), eq("first-question"), anyMap()))
                .thenReturn(new PromptManager.PromptPair("system", "user"));
        when(routingChatService.callWithFirstPacketProbeSupplier(any(Supplier.class), anyString(), anyString(), any(ModelRouteType.class), any(TimeoutHint.class), anyString()))
                .thenReturn("请你先做一个简短的自我介绍，然后聊聊 Spring Boot 自动配置原理。");

        ragService.generateFirstQuestion("负责过订单系统重构", "Spring Boot", "高级后端开发", false, List.of());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(promptManager, atLeastOnce()).renderSplit(eq("interviewer"), eq("first-question"), captor.capture());
        String skillBlock = String.valueOf(captor.getValue().get("skillBlock"));
        assertTrue(skillBlock.contains("Question Strategy"));
        assertTrue(skillBlock.contains("首题生成"));
    }

    @Test
    void shouldUseMcpSearchResultWhenAvailable() {
        ragRetrievalProperties.setWebFallbackMode(RagRetrievalProperties.WebFallbackMode.ON_EMPTY);
        when(agentSkillService.resolveSkillBlock("query-optimizer")).thenReturn("");
        when(routingChatService.callWithFirstPacketProbeSupplier(any(Supplier.class), nullable(String.class), any(ModelRouteType.class), any(TimeoutHint.class), anyString()))
                .thenReturn("Java 并发");
        when(observabilitySwitchProperties.isRagTraceEnabled()).thenReturn(false);
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of());
        when(mcpGatewayService.invoke(anyString(), eq("web.search"), anyMap(), anyMap())).thenReturn(Map.of(
                "status", "ok",
                "result", List.of(
                        Map.of("title", "并发基础", "snippet", "线程与锁的关系")
                )
        ));

        RAGService.KnowledgePacket packet = ragService.buildKnowledgePacket("什么是并发", "");

        assertTrue(packet.webFallbackUsed());
        assertTrue(packet.context().contains("并发基础"));
    }

    @Test
    void shouldStillFallbackToWebWhenOnlyGraphHintsExist() {
        ragRetrievalProperties.setWebFallbackMode(RagRetrievalProperties.WebFallbackMode.ON_EMPTY);
        when(agentSkillService.resolveSkillBlock("query-optimizer")).thenReturn("");
        when(routingChatService.callWithFirstPacketProbeSupplier(any(Supplier.class), nullable(String.class), any(ModelRouteType.class), any(TimeoutHint.class), anyString()))
                .thenReturn("HashMap 树化");
        when(observabilitySwitchProperties.isRagTraceEnabled()).thenReturn(false);
        when(lexicalIndexService.tokenize(anyString())).thenReturn(List.of("HashMap"));
        when(lexicalIndexService.searchIntentDirected(anyString(), any(), anyInt())).thenReturn(List.of());
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of());
        when(techConceptRepository.findRelatedConceptSnippetsBatch(any())).thenReturn(List.of(
                batchGraphConcept("HashMap", "红黑树", "JDK 8 中链表过长时会树化，避免哈希桶退化。", "Algorithm")
        ));
        when(mcpGatewayService.invoke(anyString(), anyString(), anyMap(), anyMap())).thenReturn(Map.of(
                "status", "fallback_stub",
                "result", Map.of()
        ));
        when(webSearchTool.run(any())).thenReturn(List.of("Web info about HashMap treeify"));

        RAGService.KnowledgePacket packet = ragService.buildKnowledgePacket("HashMap 为什么会树化", "");

        assertTrue(packet.webFallbackUsed());
        assertTrue(packet.context().contains("Web info about HashMap treeify"));
        verify(webSearchTool, times(1)).run(any(WebSearchTool.Query.class));
    }

    @Test
    void shouldInjectExecutableCodingCoachIntoCodingQuestionPrompt() {
        when(promptManager.renderSplit(eq("coding-coach"), eq("coding-question"), anyMap()))
                .thenReturn(new PromptManager.PromptPair("system", "user"));
        when(routingChatService.callWithFirstPacketProbeSupplier(any(Supplier.class), anyString(), anyString(), any(ModelRouteType.class), any(TimeoutHint.class), anyString()))
                .thenReturn("请完成一道 Two Sum 算法题。");

        ragService.generateCodingQuestion("数组与字符串", "medium", "高级后端开发", List.of());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(promptManager, atLeastOnce()).renderSplit(eq("coding-coach"), eq("coding-question"), captor.capture());
        String skillBlock = String.valueOf(captor.getValue().get("skillBlock"));
        assertTrue(skillBlock.contains("Coding Coach"));
        assertTrue(skillBlock.contains("任务: 出题"));
    }

    @Test
    void shouldInjectExecutableCodingCoachIntoCodingEvaluationPrompt() {
        when(promptManager.renderSplit(eq("coding-coach"), eq("coding-evaluation"), anyMap()))
                .thenReturn(new PromptManager.PromptPair("system", "user"));
        when(routingChatService.callWithFirstPacketProbeSupplier(any(Supplier.class), anyString(), anyString(), any(ModelRouteType.class), any(TimeoutHint.class), anyString()))
                .thenReturn("{\"score\":80,\"feedback\":\"ok\",\"nextHint\":\"补复杂度\",\"nextQuestion\":\"再优化一下空间复杂度\"}");

        ragService.evaluateCodingAnswer("算法", "medium", "Two Sum", "使用 HashMap 一次遍历");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(promptManager, atLeastOnce()).renderSplit(eq("coding-coach"), eq("coding-evaluation"), captor.capture());
        String skillBlock = String.valueOf(captor.getValue().get("skillBlock"));
        assertTrue(skillBlock.contains("Coding Coach"));
        assertTrue(skillBlock.contains("任务: 评估"));
    }

    @Test
    void shouldInjectExecutableLearningPlannerIntoLearningPlanPrompt() {
        when(promptManager.renderSplit(eq("interviewer"), eq("learning-plan"), anyMap()))
                .thenReturn(new PromptManager.PromptPair("system", "user"));
        when(routingChatService.callWithFirstPacketProbeSupplier(any(Supplier.class), anyString(), anyString(), any(ModelRouteType.class), any(TimeoutHint.class), anyString()))
                .thenReturn("Day1: 学概念 | 练习: 看一题 | 复盘: 记错点");

        ragService.generateLearningPlan("Redis", "缓存一致性薄弱", "最近低分，边界条件经常漏");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(promptManager, atLeastOnce()).renderSplit(eq("interviewer"), eq("learning-plan"), captor.capture());
        String skillBlock = String.valueOf(captor.getValue().get("skillBlock"));
        assertTrue(skillBlock.contains("Learning Plan Strategy"));
        assertTrue(skillBlock.contains("聚焦短板"));
    }

    @Test
    void shouldInjectExecutableReportStrategyIntoFinalReportPrompt() {
        when(promptManager.renderSplit(eq("interviewer"), eq("final-report"), anyMap()))
                .thenReturn(new PromptManager.PromptPair("system", "user"));
        when(routingChatService.call(anyString(), anyString(), any(ModelRouteType.class), anyString()))
                .thenReturn("<summary>整体表现一般</summary>");

        List<Question> history = List.of(
                new Question("Redis 为什么快", "因为在内存", 45, 0, 0, 0, 0, "", "", "", "只回答了表层现象"),
                new Question("说一下 JVM 垃圾回收器", "回答不完整", 68, 0, 0, 0, 0, "", "", "", "缺少分代与收集器对比")
        );

        ragService.generateFinalReport("Java", history, "下一轮重点补 JVM 与 Redis 原理链路", "前几轮存在基础概念混淆");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(promptManager, atLeastOnce()).renderSplit(eq("interviewer"), eq("final-report"), captor.capture());
        String skillBlock = String.valueOf(captor.getValue().get("skillBlock"));
        assertTrue(skillBlock.contains("Interview Report Strategy"));
        assertTrue(skillBlock.contains("Redis 为什么快"));
        assertTrue(skillBlock.contains("targetedSuggestion"));
    }

    /**
     * 构造 GraphRAG 投影视图测试桩，便于验证描述性文本拼装逻辑。
     */
    private TechConceptSnippetView graphConcept(String name, String description, String type) {
        return new TechConceptSnippetView() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return description;
            }

            @Override
            public String getType() {
                return type;
            }
        };
    }

    private io.github.imzmq.interview.graph.domain.BatchedConceptSnippetView batchGraphConcept(String anchor, String name, String description, String type) {
        return new io.github.imzmq.interview.graph.domain.BatchedConceptSnippetView() {
            @Override
            public String getAnchor() {
                return anchor;
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return description;
            }

            @Override
            public String getType() {
                return type;
            }
        };
    }
}









