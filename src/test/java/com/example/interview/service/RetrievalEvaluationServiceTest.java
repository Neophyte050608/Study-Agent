package com.example.interview.service;

import com.example.interview.config.ObservabilitySwitchProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ResourceLoader;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetrievalEvaluationServiceTest {

    @Mock
    private RAGService ragService;

    @Mock
    private ObservabilitySwitchProperties observabilitySwitchProperties;

    @Mock
    private ResourceLoader resourceLoader;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RetrievalEvaluationService service;

    @BeforeEach
    void setUp() {
        service = new RetrievalEvaluationService(ragService, observabilitySwitchProperties, resourceLoader, objectMapper);
        when(observabilitySwitchProperties.isRetrievalEvalEnabled()).thenReturn(true);
    }

    @Test
    void shouldRunCustomEvalWithRecallAndMRR() {
        List<RetrievalEvaluationService.EvalCase> cases = List.of(
                new RetrievalEvaluationService.EvalCase("Spring事务传播行为有哪些", List.of("传播", "事务"), "test"),
                new RetrievalEvaluationService.EvalCase("Redis为什么快", List.of("redis", "内存"), "test")
        );

        Document doc1 = new Document("事务传播包括 REQUIRED、REQUIRES_NEW 等传播行为。");
        Document doc2 = new Document("Redis 的核心优势是内存访问速度快。");

        when(ragService.buildKnowledgePacket(eq("Spring事务传播行为有哪些"), eq(""), eq(false)))
                .thenReturn(new RAGService.KnowledgePacket("Spring事务传播行为有哪些", List.of(doc1), "上下文", "1. [note1]", false));
        when(ragService.buildKnowledgePacket(eq("Redis为什么快"), eq(""), eq(false)))
                .thenReturn(new RAGService.KnowledgePacket("Redis为什么快", List.of(doc2), "上下文", "1. [note2]", false));

        RetrievalEvaluationService.RetrievalEvalReport report = service.runCustomEval(cases);

        assertEquals(2, report.totalCases());
        assertEquals(2, report.hitCases());
        assertEquals(1.0D, report.recallAt1());
        assertEquals(1.0D, report.mrr());
        assertTrue(report.runId() != null && !report.runId().isBlank());
        verify(ragService).buildKnowledgePacket(eq("Spring事务传播行为有哪些"), eq(""), eq(false));
        verify(ragService).buildKnowledgePacket(eq("Redis为什么快"), eq(""), eq(false));
    }

    @Test
    void shouldParseCsvCasesWithChineseSeparators() {
        List<RetrievalEvaluationService.EvalCase> cases = service.parseCasesFromCsv("""
                query,keywords
                Redis为什么快,redis|内存|单线程
                JVM垃圾收集器对比,G1，ZGC；停顿时间
                """);

        assertEquals(2, cases.size());
        assertEquals(3, cases.get(0).expectedKeywords().size());
        assertTrue(cases.get(1).expectedKeywords().contains("ZGC"));
    }

    @Test
    void shouldKeepRecentReportsAndSupportComparisonInMemory() {
        List<RetrievalEvaluationService.EvalCase> cases = List.of(
                new RetrievalEvaluationService.EvalCase("HashMap 为什么快", List.of("hash", "数组"), "manual")
        );

        when(ragService.buildKnowledgePacket(eq("HashMap 为什么快"), eq(""), eq(false)))
                .thenReturn(new RAGService.KnowledgePacket(
                        "HashMap 为什么快",
                        List.of(new Document("HashMap 基于数组和 hash 寻址。")),
                        "上下文",
                        "1. [hash-1.md]",
                        false
                ))
                .thenReturn(new RAGService.KnowledgePacket(
                        "HashMap 为什么快",
                        List.of(new Document("这里只提到了扩容，没有覆盖预期词。")),
                        "上下文",
                        "1. [hash-2.md]",
                        false
                ));

        RetrievalEvaluationService.RetrievalEvalReport first = service.runCustomEval(cases, "manual", "baseline");
        RetrievalEvaluationService.RetrievalEvalReport second = service.runCustomEval(cases, "manual", "candidate");

        List<RetrievalEvaluationService.RetrievalEvalRunSummary> summaries = service.listRecentRuns(10);
        assertEquals(2, summaries.size());
        assertEquals(second.runId(), summaries.get(0).runId());

        RetrievalEvaluationService.RetrievalEvalReport detail = service.getRunDetail(first.runId());
        assertNotNull(detail);
        assertEquals(first.runId(), detail.runId());

        RetrievalEvaluationService.RetrievalEvalComparison comparison = service.compareRuns(first.runId(), second.runId());
        assertNotNull(comparison);
        assertEquals(first.runId(), comparison.baselineRunId());
        assertEquals(second.runId(), comparison.candidateRunId());
        assertEquals(0, comparison.improvedCaseCount());
        assertEquals(1, comparison.regressedCaseCount());
        assertEquals(1, comparison.changedCases().size());
    }

    @Test
    void shouldPreserveEvalRunOptionsInReport() {
        List<RetrievalEvaluationService.EvalCase> cases = List.of(
                new RetrievalEvaluationService.EvalCase("Redis 为什么快", List.of("redis", "内存"), "manual")
        );
        when(ragService.buildKnowledgePacket(eq("Redis 为什么快"), eq(""), eq(false)))
                .thenReturn(new RAGService.KnowledgePacket(
                        "Redis 为什么快",
                        List.of(new Document("Redis 基于内存访问，因此响应快。")),
                        "上下文",
                        "1. [redis.md]",
                        false
                ));

        RetrievalEvaluationService.RetrievalEvalReport report = service.runCustomEval(
                cases,
                new RetrievalEvaluationService.EvalRunOptions(
                        "manual",
                        "redis-baseline",
                        "exp-rag-001",
                        java.util.Map.of("rerankTopK", 8, "fusionMode", "RRF"),
                        "验证 Redis 类问题的召回稳定性"
                )
        );

        assertEquals("redis-baseline", report.runLabel());
        assertEquals("exp-rag-001", report.experimentTag());
        assertEquals("RRF", report.parameterSnapshot().get("fusionMode"));
        assertEquals("验证 Redis 类问题的召回稳定性", report.notes());
    }
    @Test
    void shouldAggregateTrendAndFailureClusters() {
        // 第一轮让两个主题都命中，用于生成基线评测结果。
        when(ragService.buildKnowledgePacket(eq("Redis trend query"), eq(""), eq(false)))
                .thenReturn(new RAGService.KnowledgePacket(
                        "Redis trend query",
                        List.of(new Document("Redis in-memory access keeps latency low.")),
                        "context",
                        "1. [redis.md]",
                        false
                ));
        when(ragService.buildKnowledgePacket(eq("MySQL index query"), eq(""), eq(false)))
                .thenReturn(new RAGService.KnowledgePacket(
                        "MySQL index query",
                        List.of(new Document("MySQL BTree indexes reduce full table scans and improve lookup speed.")),
                        "context",
                        "1. [mysql-pass.md]",
                        false
                ))
                .thenReturn(new RAGService.KnowledgePacket(
                        "MySQL index query",
                        List.of(new Document("This snippet only talks about slow SQL and never mentions index design.")),
                        "context",
                        "1. [mysql-fail.md]",
                        false
                ));

        List<RetrievalEvaluationService.EvalCase> cases = List.of(
                new RetrievalEvaluationService.EvalCase("Redis trend query", List.of("redis", "memory"), "cache"),
                new RetrievalEvaluationService.EvalCase("MySQL index query", List.of("btree", "full table scans"), "database")
        );

        service.runCustomEval(
                cases,
                new RetrievalEvaluationService.EvalRunOptions(
                        "manual",
                        "trend-baseline",
                        "baseline",
                        Map.of("rerankTopK", 8),
                        "baseline run"
                )
        );
        RetrievalEvaluationService.RetrievalEvalReport candidate = service.runCustomEval(
                cases,
                new RetrievalEvaluationService.EvalRunOptions(
                        "manual",
                        "trend-candidate",
                        "candidate",
                        Map.of("rerankTopK", 12),
                        "candidate run"
                )
        );

        RetrievalEvaluationService.RetrievalEvalTrend trend = service.getTrend(10);
        assertEquals(2, trend.runs().size());
        assertEquals(1.0D, trend.bestRecallAt5());
        assertEquals(0.75D, trend.avgRecallAt5());
        assertEquals(1L, trend.experimentDistribution().get("baseline"));
        assertEquals(1L, trend.experimentDistribution().get("candidate"));

        List<RetrievalEvaluationService.RetrievalEvalFailureCluster> clusters = service.clusterFailures(candidate.runId());
        assertNotNull(clusters);
        assertEquals(1, clusters.size());
        assertEquals("database", clusters.get(0).tag());
        assertEquals(1, clusters.get(0).failedCaseCount());
        assertEquals("MySQL index query", clusters.get(0).sampleQueries().get(0));
        assertTrue(clusters.get(0).representativeKeywords().contains("btree"));
        assertNull(service.clusterFailures("missing-run"));
    }

    @Test
    void shouldExposeBuiltInParameterTemplates() {
        // 参数模板是前端快速发起实验的固定入口，需要保证模板数量和关键字段稳定。
        List<RetrievalEvaluationService.RetrievalEvalParameterTemplate> templates = service.listParameterTemplates();

        assertEquals(3, templates.size());
        assertEquals("baseline-rrf", templates.get(0).templateId());
        assertEquals("RRF", templates.get(0).parameterSnapshot().get("fusionMode"));
        assertEquals(Boolean.TRUE, templates.get(2).parameterSnapshot().get("allowWebFallback"));
    }
}
