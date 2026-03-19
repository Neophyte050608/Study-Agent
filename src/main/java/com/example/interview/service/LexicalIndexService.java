package com.example.interview.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@Service
public class LexicalIndexService {

    private static final Logger logger = LoggerFactory.getLogger(LexicalIndexService.class);
    private static final String INDEX_FILE = "lexical_index.json";

    private final ObjectMapper objectMapper;
    private final Map<String, LexicalRecord> records = new ConcurrentHashMap<>();

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
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}\\s_#-]", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .lines()
                .flatMap(line -> List.of(line.split(" ")).stream())
                .map(String::trim)
                .filter(token -> token.length() >= 2)
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
