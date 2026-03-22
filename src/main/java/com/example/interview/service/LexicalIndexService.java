package com.example.interview.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 词法索引服务 (Lexical Index Service)。
 * 
 * 职责：
 * 1. 全文索引：基于内存提供轻量级的关键词/全文索引能力，弥补向量检索在“精确匹配”上的不足。
 * 2. 混合检索支持：为 RAGService 提供 TF-IDF/BM25 风格的检索结果，用于 RRF (Reciprocal Rank Fusion) 融合。
 * 3. 实时更新：支持知识入库时的增量索引与删除。
 */
@Service
public class LexicalIndexService {

    private static final Logger logger = LoggerFactory.getLogger(LexicalIndexService.class);
    private static final String INDEX_FILE = "lexical_index.json";

    private final ObjectMapper objectMapper;
    private final Map<String, LexicalRecord> records = new ConcurrentHashMap<>();
    
    // 引入 Jieba 中文分词器
    private final JiebaSegmenter segmenter = new JiebaSegmenter();

    public LexicalIndexService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        loadIndex();
    }

    public void upsertDocuments(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        for (Document doc : documents) {
            if (doc == null || doc.getId() == null || doc.getText() == null || doc.getText().isBlank()) {
                continue;
            }
            Map<String, Object> metadata = doc.getMetadata();
            String filePath = metadata == null ? "" : String.valueOf(metadata.getOrDefault("file_path", ""));
            String tags = metadata == null ? "" : String.valueOf(metadata.getOrDefault("knowledge_tags", ""));
            String sourceType = metadata == null ? "obsidian" : String.valueOf(metadata.getOrDefault("source_type", "obsidian"));
            String normalized = doc.getText().replaceAll("\\s+", " ").trim();
            records.put(doc.getId(), new LexicalRecord(doc.getId(), normalized, filePath, tags, sourceType));
        }
        saveIndex();
    }

    public void removeByIds(List<String> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            return;
        }
        for (String id : docIds) {
            if (id != null) {
                records.remove(id);
            }
        }
        saveIndex();
    }

    public List<Document> search(String query, int topK) {
        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty() || records.isEmpty() || topK <= 0) {
            return List.of();
        }
        int totalDocs = records.size();
        Map<String, Integer> docFreq = new HashMap<>();
        for (LexicalRecord record : records.values()) {
            Set<String> tokenSet = new HashSet<>(tokenize(record.text));
            for (String token : tokenSet) {
                docFreq.merge(token, 1, Integer::sum);
            }
        }

        List<ScoredRecord> scored = new ArrayList<>();
        for (LexicalRecord record : records.values()) {
            List<String> docTokens = tokenize(record.text);
            if (docTokens.isEmpty()) {
                continue;
            }
            Map<String, Long> tf = docTokens.stream().collect(Collectors.groupingBy(token -> token, Collectors.counting()));
            double score = 0.0;
            for (String token : queryTokens) {
                long termFreq = tf.getOrDefault(token, 0L);
                if (termFreq == 0) {
                    continue;
                }
                int df = docFreq.getOrDefault(token, 0);
                double idf = Math.log(1.0 + (double) (totalDocs + 1) / (df + 1));
                score += termFreq * idf;
            }
            if (score > 0.0) {
                scored.add(new ScoredRecord(record, score));
            }
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredRecord::score).reversed())
                .limit(topK)
                .map(item -> {
                    Document doc = new Document(item.record().text);
                    doc.getMetadata().put("file_path", item.record().filePath);
                    doc.getMetadata().put("knowledge_tags", item.record().knowledgeTags);
                    doc.getMetadata().put("source_type", item.record().sourceType == null || item.record().sourceType.isBlank() ? "obsidian" : item.record().sourceType);
                    doc.getMetadata().put("lexical_score", item.score());
                    doc.getMetadata().put("doc_id", item.record().docId);
                    return doc;
                })
                .collect(Collectors.toList());
    }

    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        // 使用 Jieba 分词器进行中文分词，提高对“微服务”、“分库分表”等专有名词的识别率
        // 采用 SEARCH 模式（或 INDEX 模式），适合用于搜索引擎构建倒排索引
        List<SegToken> tokens = segmenter.process(text, JiebaSegmenter.SegMode.SEARCH);
        
        return tokens.stream()
                .map(token -> token.word.toLowerCase(Locale.ROOT).trim())
                // 过滤掉过短的无意义字符以及标点符号
                .filter(word -> word.length() >= 2 && !word.matches("^[\\p{Punct}\\s]+$"))
                .collect(Collectors.toList());
    }

    private synchronized void loadIndex() {
        File file = new File(INDEX_FILE);
        if (!file.exists()) {
            return;
        }
        try {
            Map<String, LexicalRecord> loaded = objectMapper.readValue(file, new TypeReference<Map<String, LexicalRecord>>() {});
            records.clear();
            if (loaded != null) {
                records.putAll(loaded);
            }
            logger.info("Loaded lexical index: {}", records.size());
        } catch (IOException e) {
            logger.warn("Failed to load lexical index", e);
        }
    }

    private synchronized void saveIndex() {
        try {
            objectMapper.writeValue(new File(INDEX_FILE), records);
        } catch (IOException e) {
            logger.warn("Failed to save lexical index", e);
        }
    }

    public static class LexicalRecord {
        public String docId;
        public String text;
        public String filePath;
        public String knowledgeTags;
        public String sourceType;

        public LexicalRecord() {
        }

        public LexicalRecord(String docId, String text, String filePath, String knowledgeTags, String sourceType) {
            this.docId = docId;
            this.text = text;
            this.filePath = filePath;
            this.knowledgeTags = knowledgeTags;
            this.sourceType = sourceType;
        }
    }

    private record ScoredRecord(LexicalRecord record, double score) {
    }
}
