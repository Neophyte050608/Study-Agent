package com.example.interview.service;

import com.example.interview.config.ObservabilitySwitchProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ResourceLoader;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetrievalEvaluationServiceTest {

    @Mock
    private VectorStore vectorStore;

    @Mock
    private LexicalIndexService lexicalIndexService;

    @Mock
    private ObservabilitySwitchProperties observabilitySwitchProperties;

    @Mock
    private ResourceLoader resourceLoader;

    private ObjectMapper objectMapper = new ObjectMapper();

    private RetrievalEvaluationService service;

    @BeforeEach
    void setUp() {
        service = new RetrievalEvaluationService(vectorStore, lexicalIndexService, observabilitySwitchProperties, resourceLoader, objectMapper);
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
        
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenReturn(List.of(doc1), List.of(doc2));
        when(lexicalIndexService.search(anyString(), anyInt())).thenReturn(List.of());

        RetrievalEvaluationService.RetrievalEvalReport report = service.runCustomEval(cases);

        assertEquals(2, report.totalCases());
        assertEquals(2, report.hitCases());
        assertEquals(1.0, report.recallAt1());
        assertEquals(1.0, report.mrr());
    }
}
