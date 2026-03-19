package com.example.interview.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RetrievalEvaluationService {

    private final VectorStore vectorStore;
    private final LexicalIndexService lexicalIndexService;

    public RetrievalEvaluationService(VectorStore vectorStore, LexicalIndexService lexicalIndexService) {
        this.vectorStore = vectorStore;
        this.lexicalIndexService = lexicalIndexService;
    }

    public RetrievalEvalReport runDefaultEval() {
        List<EvalCase> cases = List.of(
                new EvalCase("Spring事务传播行为有哪些", List.of("传播", "事务")),
                new EvalCase("Redis为什么快", List.of("redis", "内存")),
                new EvalCase("JVM垃圾回收器对比", List.of("gc", "垃圾回收"))
        );
        return runCustomEval(cases);
    }

    public RetrievalEvalReport runCustomEval(List<EvalCase> cases) {
        List<EvalCase> normalizedCases = normalizeCases(cases);
        if (normalizedCases.isEmpty()) {
            return new RetrievalEvalReport(Instant.now().toString(), 0, 0, 0.0, List.of());
        }
        List<EvalResult> results = new ArrayList<>();
        int pass = 0;
        for (EvalCase item : normalizedCases) {
            EvalResult result = evaluateSingle(item);
            results.add(result);
            if (result.hit()) {
                pass++;
            }
        }
        double hitRate = normalizedCases.isEmpty() ? 0.0 : (double) pass / normalizedCases.size();
        return new RetrievalEvalReport(Instant.now().toString(), normalizedCases.size(), pass, hitRate, results);
    }

    public List<EvalCase> parseCasesFromCsv(String csvText) {
        if (csvText == null || csvText.isBlank()) {
            return List.of();
        }
        String[] lines = csvText.split("\\R");
        List<EvalCase> cases = new ArrayList<>();
        int start = 0;
        if (lines.length > 0 && lines[0].toLowerCase(Locale.ROOT).contains("query")) {
            start = 1;
        }
        for (int i = start; i < lines.length; i++) {
            String line = lines[i];
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] parts = line.split(",", 2);
            if (parts.length == 0) {
                continue;
            }
            String query = parts[0].trim();
            if (query.isBlank()) {
                continue;
            }
            List<String> keywords = List.of();
            if (parts.length > 1) {
                keywords = List.of(parts[1].split("[|；;，,]")).stream()
                        .map(String::trim)
                        .filter(item -> !item.isBlank())
                        .collect(Collectors.toList());
            }
            cases.add(new EvalCase(query, keywords));
        }
        return normalizeCases(cases);
    }

    private EvalResult evaluateSingle(EvalCase evalCase) {
        List<Document> vectorDocs = List.of();
        try {
            vectorDocs = vectorStore.similaritySearch(SearchRequest.builder().query(evalCase.query()).topK(5).build());
        } catch (Exception ignored) {
        }
        List<Document> lexicalDocs = lexicalIndexService.search(evalCase.query(), 5);
        Set<String> merged = new LinkedHashSet<>();
        vectorDocs.stream().map(Document::getText).forEach(merged::add);
        lexicalDocs.stream().map(Document::getText).forEach(merged::add);
        List<String> snippets = merged.stream().filter(item -> item != null && !item.isBlank()).limit(5).toList();
        boolean hit = snippets.stream().anyMatch(text -> containsAnyKeyword(text, evalCase.expectedKeywords()));
        return new EvalResult(evalCase.query(), evalCase.expectedKeywords(), hit, snippets);
    }

    private boolean containsAnyKeyword(String text, List<String> keywords) {
        if (text == null || text.isBlank() || keywords == null || keywords.isEmpty()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        return keywords.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(item -> item.toLowerCase(Locale.ROOT))
                .anyMatch(normalized::contains);
    }

    private List<EvalCase> normalizeCases(List<EvalCase> cases) {
        if (cases == null || cases.isEmpty()) {
            return List.of();
        }
        return cases.stream()
                .filter(item -> item != null && item.query() != null && !item.query().isBlank())
                .map(item -> {
                    List<String> keywords = item.expectedKeywords() == null ? List.of() : item.expectedKeywords().stream()
                            .map(String::trim)
                            .filter(word -> !word.isBlank())
                            .distinct()
                            .collect(Collectors.toList());
                    return new EvalCase(item.query().trim(), keywords);
                })
                .collect(Collectors.toList());
    }

    public record RetrievalEvalReport(
            String timestamp,
            int totalCases,
            int hitCases,
            double hitRate,
            List<EvalResult> results
    ) {
    }

    public record EvalResult(
            String query,
            List<String> expectedKeywords,
            boolean hit,
            List<String> retrievedSnippets
    ) {
    }

    public record EvalCase(String query, List<String> expectedKeywords) {
    }
}
