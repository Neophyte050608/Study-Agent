package com.example.interview.service;

import com.example.interview.config.ObservabilitySwitchProperties;
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

/**
 * 检索效果评测服务 (Retrieval Evaluation Service)。
 * 
 * 职责：
 * 1. 离线评测：利用预设的 EvalCase 或上传的 CSV 评测集，对现有的向量库 + 词法索引进行召回率测试。
 * 2. 命中校验：根据预期关键词 (expectedKeywords) 自动判断检索出的分块是否包含正确答案。
 * 3. 效果量化：输出 Hit Rate (命中率) 报告，指导向量模型调优或切分策略优化。
 */
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ResourceLoader;

import java.io.InputStream;
import java.util.*;

@Service
public class RetrievalEvaluationService {

    private final VectorStore vectorStore;
    private final LexicalIndexService lexicalIndexService;
    private final ObservabilitySwitchProperties observabilitySwitchProperties;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;

    public RetrievalEvaluationService(VectorStore vectorStore, LexicalIndexService lexicalIndexService, 
                                     ObservabilitySwitchProperties observabilitySwitchProperties,
                                     ResourceLoader resourceLoader,
                                     ObjectMapper objectMapper) {
        this.vectorStore = vectorStore;
        this.lexicalIndexService = lexicalIndexService;
        this.observabilitySwitchProperties = observabilitySwitchProperties;
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
    }

    public RetrievalEvalReport runDefaultEval() {
        ensureEvalEnabled();
        try {
            InputStream is = resourceLoader.getResource("classpath:eval/rag_ground_truth.json").getInputStream();
            List<EvalCase> cases = objectMapper.readValue(is, new TypeReference<List<EvalCase>>() {});
            return runCustomEval(cases);
        } catch (Exception e) {
            // Fallback to hardcoded cases if file not found
            List<EvalCase> cases = List.of(
                    new EvalCase("Spring事务传播行为有哪些", List.of("传播", "事务"), "default"),
                    new EvalCase("Redis为什么快", List.of("redis", "内存"), "default"),
                    new EvalCase("JVM垃圾回收器对比", List.of("gc", "垃圾回收"), "default")
            );
            return runCustomEval(cases);
        }
    }

    public RetrievalEvalReport runCustomEval(List<EvalCase> cases) {
        ensureEvalEnabled();
        List<EvalCase> normalizedCases = normalizeCases(cases);
        if (normalizedCases.isEmpty()) {
            return new RetrievalEvalReport(Instant.now().toString(), 0, 0, 0.0, 0.0, 0.0, 0.0, List.of());
        }
        
        List<EvalResult> results = new ArrayList<>();
        int hitAt1 = 0;
        int hitAt3 = 0;
        int hitAt5 = 0;
        double sumRR = 0.0;
        
        for (EvalCase item : normalizedCases) {
            EvalResult result = evaluateSingle(item);
            results.add(result);
            
            if (result.rank() == 1) hitAt1++;
            if (result.rank() > 0 && result.rank() <= 3) hitAt3++;
            if (result.rank() > 0 && result.rank() <= 5) hitAt5++;
            if (result.rank() > 0) {
                sumRR += 1.0 / result.rank();
            }
        }
        
        int total = normalizedCases.size();
        double recallAt1 = (double) hitAt1 / total;
        double recallAt3 = (double) hitAt3 / total;
        double recallAt5 = (double) hitAt5 / total;
        double mrr = sumRR / total;
        
        return new RetrievalEvalReport(
                Instant.now().toString(), 
                total, 
                hitAt5, 
                recallAt1, 
                recallAt3, 
                recallAt5, 
                mrr, 
                results
        );
    }

    private EvalResult evaluateSingle(EvalCase evalCase) {
        List<Document> vectorDocs = List.of();
        try {
            vectorDocs = vectorStore.similaritySearch(SearchRequest.builder().query(evalCase.query()).topK(5).build());
        } catch (Exception ignored) {}
        
        List<Document> lexicalDocs = lexicalIndexService.search(evalCase.query(), 5);
        
        // 合并并保留排名信息
        List<String> combinedSnippets = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        
        int max = 5;
        for (int i = 0; i < max; i++) {
            if (i < vectorDocs.size()) {
                String text = vectorDocs.get(i).getText();
                if (seen.add(text)) combinedSnippets.add(text);
            }
            if (i < lexicalDocs.size()) {
                String text = lexicalDocs.get(i).getText();
                if (seen.add(text)) combinedSnippets.add(text);
            }
            if (combinedSnippets.size() >= 5) break;
        }
        
        List<String> finalSnippets = combinedSnippets.stream().limit(5).toList();
        
        int firstHitRank = -1;
        for (int i = 0; i < finalSnippets.size(); i++) {
            if (containsAnyKeyword(finalSnippets.get(i), evalCase.expectedKeywords())) {
                firstHitRank = i + 1;
                break;
            }
        }
        
        return new EvalResult(evalCase.query(), evalCase.expectedKeywords(), firstHitRank > 0, firstHitRank, finalSnippets);
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
                    return new EvalCase(item.query().trim(), keywords, item.tag());
                })
                .collect(Collectors.toList());
    }

    public List<EvalCase> parseCasesFromCsv(String csvText) {
        ensureEvalEnabled();
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
            cases.add(new EvalCase(query, keywords, "csv"));
        }
        return normalizeCases(cases);
    }

    public boolean isEvalEnabled() {
        return observabilitySwitchProperties.isRetrievalEvalEnabled();
    }

    private void ensureEvalEnabled() {
        if (!observabilitySwitchProperties.isRetrievalEvalEnabled()) {
            throw new IllegalStateException("召回率评测已关闭，请设置 app.observability.retrieval-eval-enabled=true 后重试");
        }
    }

    public record RetrievalEvalReport(
            String timestamp,
            int totalCases,
            int hitCases,
            double recallAt1,
            double recallAt3,
            double recallAt5,
            double mrr,
            List<EvalResult> results
    ) {
        @Deprecated
        public double getHitRate() { return recallAt5; }
    }

    public record EvalResult(
            String query,
            List<String> expectedKeywords,
            boolean hit,
            int rank,
            List<String> retrievedSnippets
    ) {
    }

    public record EvalCase(String query, List<String> expectedKeywords, String tag) {
    }
}
