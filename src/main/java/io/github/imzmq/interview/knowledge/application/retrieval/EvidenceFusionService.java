package io.github.imzmq.interview.knowledge.application.retrieval;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;
import io.github.imzmq.interview.knowledge.application.indexing.LexicalIndexService;
import io.github.imzmq.interview.knowledge.application.indexing.RetrievalTokenizerService;

/**
 * 既有排序策略：RRF、query overlap 重排与 fallback 质量分数，不执行通道 I/O。
 * 重排复用词法 tokenizer，质量分数复用 RetrievalTokenizerService，保持原评测口径。
 */
@Service
public class EvidenceFusionService {
    private final LexicalIndexService lexicalIndexService;
    private final RetrievalTokenizerService retrievalTokenizerService;

    public EvidenceFusionService(LexicalIndexService lexicalIndexService, RetrievalTokenizerService retrievalTokenizerService) {
        this.lexicalIndexService = lexicalIndexService;
        this.retrievalTokenizerService = retrievalTokenizerService;
    }

    /** k=60 的排名融合，保留来源/通道加权与向量通道 lexical_score 加分。 */
    public List<Document> reciprocalRankFuse(List<Document> intentDirectedDocs, List<Document> globalVectorDocs) {
        Map<String, FusedCandidate> fused = new LinkedHashMap<>();
        for (int i = 0; i < intentDirectedDocs.size(); i++) {
            Document doc = intentDirectedDocs.get(i);
            String key = candidateKey(doc);
            FusedCandidate candidate = fused.computeIfAbsent(key, k -> new FusedCandidate(doc));
            double sourceBoost = sourceTypeBoost(doc);
            candidate.score += (1.0 / (60 + i + 1)) * sourceBoost * channelBoost(doc);
        }
        for (int i = 0; i < globalVectorDocs.size(); i++) {
            Document doc = globalVectorDocs.get(i);
            String key = candidateKey(doc);
            FusedCandidate candidate = fused.computeIfAbsent(key, k -> new FusedCandidate(doc));
            double sourceBoost = sourceTypeBoost(doc);
            candidate.score += (1.0 / (60 + i + 1)) * sourceBoost * channelBoost(doc);
            Object lexicalScore = doc.getMetadata().get("lexical_score");
            if (lexicalScore instanceof Number number) {
                candidate.score += number.doubleValue() * 0.01 * sourceBoost;
            }
        }
        return fused.values().stream()
                .sorted(Comparator.comparingDouble(FusedCandidate::score).reversed())
                .map(FusedCandidate::document)
                .collect(Collectors.toList());
    }

    public List<Document> rerankByQueryOverlap(RewrittenQuery query, List<Document> docs, int topK) {
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        List<String> coreTokens = lexicalIndexService.tokenize(query.coreTerms());
        List<String> expandTokens = query.expandTerms().isBlank()
                ? List.of()
                : lexicalIndexService.tokenize(query.expandTerms());
        if (coreTokens.isEmpty() && expandTokens.isEmpty()) {
            return docs.stream().limit(topK).collect(Collectors.toList());
        }
        int totalTokens = coreTokens.size() + expandTokens.size();
        Map<Document, Double> scoreMap = new HashMap<>();
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            List<String> docTokens = lexicalIndexService.tokenize(doc.getText());
            long coreHits = coreTokens.stream().filter(docTokens::contains).count();
            long expandHits = expandTokens.stream().filter(docTokens::contains).count();
            double weightedOverlap = totalTokens == 0 ? 0.0
                    : (coreHits * 1.0 + expandHits * 0.4) / totalTokens;
            double score = (weightedOverlap * 0.7 + (1.0 / (i + 1)) * 0.3) * sourceTypeBoost(doc);
            doc.getMetadata().put("retrieval_score", score);
            scoreMap.put(doc, score);
        }
        return docs.stream()
                .sorted((a, b) -> Double.compare(scoreMap.getOrDefault(b, 0.0), scoreMap.getOrDefault(a, 0.0)))
                .limit(topK)
                .collect(Collectors.toList());
    }

    public double bestRetrievalScore(String query, List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return 0.0D;
        }
        List<String> queryTokens = retrievalTokenizerService.tokenize(query);
        if (queryTokens.isEmpty()) {
            return 0.0D;
        }
        double bestScore = 0.0D;
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            List<String> docTokens = retrievalTokenizerService.tokenize(doc.getText());
            long overlap = queryTokens.stream().filter(docTokens::contains).count();
            double overlapRatio = docTokens.isEmpty()
                    ? 0.0D
                    : (double) overlap / (double) Math.min(queryTokens.size(), Math.max(1, docTokens.size()));
            double score = (overlapRatio * 0.7D + (1.0D / (i + 1)) * 0.3D) * sourceTypeBoost(doc);
            bestScore = Math.max(bestScore, score);
        }
        return bestScore;
    }

    private String candidateKey(Document doc) {
        if (doc == null) {
            return "null";
        }
        String path = String.valueOf(doc.getMetadata().getOrDefault("file_path", "unknown"));
        String text = doc.getText() == null ? "" : doc.getText().trim();
        String head = text.length() > 80 ? text.substring(0, 80) : text;
        return path + "::" + head;
    }

    private double channelBoost(Document doc) {
        if (doc == null || doc.getMetadata() == null) {
            return 1.0;
        }
        String channel = String.valueOf(doc.getMetadata().getOrDefault("retrieve_channel", "")).toLowerCase(Locale.ROOT);
        if ("intent_directed".equals(channel)) {
            return 1.12;
        }
        return 1.0;
    }

    private double sourceTypeBoost(Document doc) {
        if (doc == null || doc.getMetadata() == null) {
            return 1.0;
        }
        String sourceType = String.valueOf(doc.getMetadata().getOrDefault("source_type", "obsidian")).toLowerCase(Locale.ROOT);
        if ("interview_experience".equals(sourceType)) {
            return 1.18;
        }
        if ("web".equals(sourceType)) {
            return 0.92;
        }
        return 1.0;
    }

    private static class FusedCandidate {
        private final Document document;
        private double score;

        private FusedCandidate(Document document) {
            this.document = document;
            this.score = 0.0;
        }

        public Document document() {
            return document;
        }

        public double score() {
            return score;
        }
    }
}
