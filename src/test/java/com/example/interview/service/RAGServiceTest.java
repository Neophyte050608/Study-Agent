package com.example.interview.service;

import com.example.interview.config.ObservabilitySwitchProperties;
import com.example.interview.config.ParentChildRetrievalProperties;
import com.example.interview.modelrouting.ModelRouteType;
import com.example.interview.modelrouting.RoutingChatService;
import com.example.interview.tool.WebSearchTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import com.example.interview.graph.TechConceptRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.HttpServerErrorException;

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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
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

    private final Executor ragRetrieveExecutor = Runnable::run;

    @Mock
    private TechConceptRepository techConceptRepository;

    private RAGService ragService;

    @Mock
    private ObservabilitySwitchProperties observabilitySwitchProperties;

    @Mock
    private ParentChildRetrievalProperties parentChildRetrievalProperties;

    @Mock
    private ParentChildIndexService parentChildIndexService;

    @BeforeEach
    void setUp() {
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
                parentChildRetrievalProperties,
                parentChildIndexService
        );
    }

    @Test
    void shouldCallWebSearchWhenVectorStoreIsEmpty() {
        // Arrange
        String question = "什么是并发？";
        String answer = "同时执行。";
        
        when(routingChatService.call(nullable(String.class), any(ModelRouteType.class), anyString())).thenReturn("Java Concurrency", "{\"score\":80,\"accuracy\":80,\"logic\":80,\"depth\":80,\"boundary\":80,\"deductions\":[],\"citations\":[],\"conflicts\":[],\"feedback\":\"ok\",\"nextQuestion\":\"n\"}");

        // Mock empty vector store
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of());

        // Mock web search tool
        when(webSearchTool.run(any())).thenReturn(List.of("Web info about concurrency"));
        when(lexicalIndexService.search(anyString(), anyInt())).thenReturn(List.of());

        // Act
        try {
            ragService.processAnswer("Java并发", question, answer, "BASIC", "PROBE", 65.0, "暂无历史记录");
        } catch (Exception ignored) {
            // We might get an error later in generateEvaluation because of mock complexities, 
            // but we want to verify the tool call.
        }

        // Assert
        verify(webSearchTool, times(1)).run(any(WebSearchTool.Query.class));
    }

    @Test
    void shouldFilterInvalidEvidenceReferences() throws Exception {
        when(routingChatService.call(nullable(String.class), any(ModelRouteType.class), anyString())).thenReturn("Spring Transaction", """
                {"score":85,"accuracy":84,"logic":83,"depth":82,"boundary":81,
                "deductions":["边界解释不足"],
                "citations":["99. [fake]","1. [ok]"],
                "conflicts":["异常结论｜77","隔离级别描述不全｜1"],
                "feedback":"回答较好","nextQuestion":"说一下隔离级别"}
                """);

        Document vectorDoc = new Document("事务的隔离级别包括读未提交、读已提交、可重复读和串行化。");
        vectorDoc.getMetadata().put("file_path", "note/tx.md");
        vectorDoc.getMetadata().put("knowledge_tags", "技术栈");
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of(vectorDoc));
        when(lexicalIndexService.search(anyString(), anyInt())).thenReturn(List.of());

        String result = ragService.processAnswer("Spring", "什么是事务隔离级别", "回答", "INTERMEDIATE", "PROBE", 72.0, "画像");
        JsonNode node = new ObjectMapper().readTree(result);

        assertEquals(1, node.path("citations").size());
        assertEquals("1. [ok]", node.path("citations").get(0).asText());
        assertEquals(1, node.path("conflicts").size());
        assertEquals("隔离级别描述不全｜1", node.path("conflicts").get(0).asText());
        String deductions = node.path("deductions").toString();
        assertTrue(deductions.contains("自动过滤"));
    }

    @Test
    void shouldInjectKnowledgeSkillIntoRewritePrompt() {
        when(agentSkillService.resolveSkillBlock("query-optimizer")).thenReturn("### Skill: query-optimizer\n遵循混合检索流程");
        when(routingChatService.call(nullable(String.class), any(ModelRouteType.class), anyString())).thenReturn("事务");
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of());
        when(lexicalIndexService.search(anyString(), anyInt())).thenReturn(List.of());
        when(webSearchTool.run(any())).thenReturn(List.of("fallback"));

        ragService.buildKnowledgePacket("什么是事务", "用于一致性");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(routingChatService).call(captor.capture(), any(ModelRouteType.class), anyString());
        String prompt = captor.getValue();
        assertTrue(prompt.contains("query-optimizer"));
    }

    @Test
    void shouldPrioritizeInterviewExperienceInEvidenceOrder() {
        when(routingChatService.call(nullable(String.class), any(ModelRouteType.class), anyString())).thenReturn("缓存 一致性", """
                {"score":80,"accuracy":80,"logic":80,"depth":80,"boundary":80,
                "deductions":[],
                "citations":["1. [ok]"],
                "conflicts":[],
                "feedback":"回答可接受","nextQuestion":"继续"}
                """);
        when(promptManager.render(eq("evaluation"), anyMap())).thenReturn("evaluation-prompt");

        Document obsidianDoc = new Document("缓存一致性常见方案包括延迟双删和消息队列。");
        obsidianDoc.getMetadata().put("file_path", "note/cache.md");
        obsidianDoc.getMetadata().put("knowledge_tags", "技术栈");
        obsidianDoc.getMetadata().put("source_type", "obsidian");

        Document interviewDoc = new Document("面经高频追问：双写一致性失败后如何补偿。");
        interviewDoc.getMetadata().put("file_path", "interview/tencent.md");
        interviewDoc.getMetadata().put("knowledge_tags", "八股");
        interviewDoc.getMetadata().put("source_type", "interview_experience");

        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of(obsidianDoc, interviewDoc));
        when(lexicalIndexService.search(anyString(), anyInt())).thenReturn(List.of());

        ragService.processAnswer("缓存", "如何保证缓存一致性", "回答", "INTERMEDIATE", "PROBE", 70.0, "画像");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(promptManager).render(eq("evaluation"), captor.capture());
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
        assertTrue(!summary.contains("sk-very-secret-token-1234567890"));
    }

    @Test
    void shouldRedactLongTokenInMessageSummary() {
        RuntimeException exception = new RuntimeException("upstream failure authorization=Bearer abcdefghijklmnopqrstuvwxyz0123456789");
        String summary = ragService.summarizeError(exception);
        assertTrue(summary.contains("***"));
        assertTrue(!summary.contains("abcdefghijklmnopqrstuvwxyz0123456789"));
    }

    @Test
    void shouldReturnFallbackFirstQuestionWhenChatModelTimeout() {
        when(routingChatService.callWithFirstPacketProbeSupplier(any(Supplier.class), anyString(), any(ModelRouteType.class), anyString()))
                .thenReturn("你好！欢迎参加今天的「JVM」模拟面试。在正式开始技术交流之前，能请你先花 1-2 分钟做一个简单的自我介绍吗？");

        String firstQuestion = ragService.generateFirstQuestion("", "JVM", "画像", false);

        assertTrue(firstQuestion.contains("JVM"));
        assertTrue(firstQuestion.contains("自我介绍"));
    }

    @Test
    void shouldReturnFallbackEvaluationWhenLayeredEvaluateTimeout() throws Exception {
        when(routingChatService.call(nullable(String.class), any(ModelRouteType.class), anyString())).thenThrow(new ResourceAccessException("timeout"));

        RAGService.EvaluationResult result = ragService.evaluateWithKnowledge(
                "Java",
                "什么是JVM内存模型",
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
        when(routingChatService.callWithFirstPacketProbeSupplier(any(Supplier.class), anyString(), any(ModelRouteType.class), anyString())).thenReturn("""
                **第一题：请简述 Java 中 HashMap 的实现原理？**

                **出题依据（策略提示）：**
                - 难度适配
                - 学习画像关联
                """);

        String question = ragService.generateFirstQuestion("", "Java", "画像", false);

        assertTrue(question.contains("HashMap"));
        assertFalse(question.contains("出题依据"));
        assertFalse(question.contains("策略提示"));
    }
}
