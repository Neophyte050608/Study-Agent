package com.example.interview.service;

import com.example.interview.config.ObservabilitySwitchProperties;
import com.example.interview.modelrouting.ModelRouteType;
import com.example.interview.modelrouting.RoutingChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ResourceLoader;

import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RAGQualityEvaluationServiceTest {

    @Mock
    private RAGService ragService;

    @Mock
    private RoutingChatService routingChatService;

    @Mock
    private ObservabilitySwitchProperties observabilitySwitchProperties;

    @Mock
    private ResourceLoader resourceLoader;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RAGQualityEvaluationService service;

    @BeforeEach
    void setUp() {
        Executor directExecutor = Runnable::run;
        service = new RAGQualityEvaluationService(
                ragService,
                routingChatService,
                observabilitySwitchProperties,
                resourceLoader,
                objectMapper,
                directExecutor
        );
        when(observabilitySwitchProperties.isRagQualityEvalEnabled()).thenReturn(true);
    }

    @Test
    void shouldEvaluateSingleCaseWithMarkdownJsonResponses() {
        RAGQualityEvaluationService.QualityEvalCase evalCase = new RAGQualityEvaluationService.QualityEvalCase(
                "Spring事务传播行为有哪些",
                "应包含REQUIRED和REQUIRES_NEW等传播行为。",
                List.of("REQUIRED", "REQUIRES_NEW"),
                "spring"
        );

        when(ragService.buildKnowledgePacket(eq("Spring事务传播行为有哪些"), eq(""), eq(false)))
                .thenReturn(new RAGService.KnowledgePacket(
                        "Spring事务传播行为有哪些",
                        List.of(new Document("REQUIRED..."), new Document("REQUIRES_NEW...")),
                        "chunk-1\n\nchunk-2\n\nchunk-3",
                        "1. [a.md]",
                        false
                ));

        when(routingChatService.call(anyString(), anyString(), eq(ModelRouteType.GENERAL), eq("rag-quality-eval-generate")))
                .thenReturn("这是回答");
        when(routingChatService.call(anyString(), anyString(), eq(ModelRouteType.GENERAL), eq("rag-quality-eval")))
                .thenReturn("```json\n{\"claims\":[{\"claim\":\"c1\",\"supported\":true},{\"claim\":\"c2\",\"supported\":false}]}\n```")
                .thenReturn("```json\n{\"score\":0.8,\"reason\":\"相关性较高\"}\n```")
                .thenReturn("```json\n{\"chunks\":[{\"index\":0,\"relevant\":true},{\"index\":1,\"relevant\":false},{\"index\":2,\"relevant\":true}]}\n```")
                .thenReturn("```json\n{\"statements\":[{\"statement\":\"s1\",\"supported\":true},{\"statement\":\"s2\",\"supported\":false}]}\n```");

        RAGQualityEvaluationService.QualityEvalCaseResult result = service.evaluateSingleCase(evalCase);

        assertEquals(0.5D, result.faithfulness());
        assertEquals(0.8D, result.answerRelevancy());
        assertEquals((1.0D + (2.0D / 3.0D)) / 2.0D, result.contextPrecision());
        assertEquals(0.5D, result.contextRecall());
        assertTrue(result.rationales().containsKey("faithfulness"));
        assertTrue(result.rationales().containsKey("contextRecall"));
    }

    @Test
    void shouldFallbackToZeroWhenMetricJsonIsInvalid() {
        RAGQualityEvaluationService.QualityEvalCase evalCase = new RAGQualityEvaluationService.QualityEvalCase(
                "Redis为什么快",
                "内存访问快。",
                List.of("内存"),
                "redis"
        );

        when(ragService.buildKnowledgePacket(eq("Redis为什么快"), eq(""), eq(false)))
                .thenReturn(new RAGService.KnowledgePacket(
                        "Redis为什么快",
                        List.of(new Document("Redis依赖内存。")),
                        "chunk-1",
                        "1. [redis.md]",
                        false
                ));
        when(routingChatService.call(anyString(), anyString(), eq(ModelRouteType.GENERAL), eq("rag-quality-eval-generate")))
                .thenReturn("回答文本");
        when(routingChatService.call(anyString(), anyString(), eq(ModelRouteType.GENERAL), eq("rag-quality-eval")))
                .thenReturn("not-json")
                .thenReturn("not-json")
                .thenReturn("not-json")
                .thenReturn("not-json");

        RAGQualityEvaluationService.QualityEvalCaseResult result = service.evaluateSingleCase(evalCase);

        assertEquals(0.0D, result.faithfulness());
        assertEquals(0.0D, result.answerRelevancy());
        assertEquals(0.0D, result.contextPrecision());
        assertEquals(0.0D, result.contextRecall());
    }
}
