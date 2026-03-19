package com.example.interview.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

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

    @Test
    void shouldParseCsvCasesAndRunCustomEval() {
        RetrievalEvaluationService service = new RetrievalEvaluationService(vectorStore, lexicalIndexService);
        List<RetrievalEvaluationService.EvalCase> cases = service.parseCasesFromCsv("""
                query,expected_keywords
                Spring事务传播行为有哪些,传播|事务
                Redis为什么快,redis|内存
                """);
        assertEquals(2, cases.size());

        Document doc1 = new Document("事务传播包括 REQUIRED、REQUIRES_NEW 等传播行为。");
        Document doc2 = new Document("Redis 的核心优势是内存访问速度快。");
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of(doc1), List.of(doc2));
        when(lexicalIndexService.search(anyString(), anyInt())).thenReturn(List.of());

        RetrievalEvaluationService.RetrievalEvalReport report = service.runCustomEval(cases);

        assertEquals(2, report.totalCases());
        assertEquals(2, report.hitCases());
        assertTrue(report.hitRate() >= 1.0);
    }
}
