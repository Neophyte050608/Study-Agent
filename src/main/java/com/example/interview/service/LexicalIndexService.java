package com.example.interview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.interview.entity.LexicalIndexDO;
import com.example.interview.mapper.LexicalIndexMapper;
import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 词法索引服务 (Lexical Index Service)。
 * 
 * 职责：
 * 1. 全文索引：基于数据库提供关键词匹配能力，弥补向量检索在“精确匹配”上的不足。
 * 2. 混合检索支持：为 RAGService 提供 TF-IDF/BM25 风格的检索结果，用于 RRF (Reciprocal Rank Fusion) 融合。
 * 3. 实时更新：支持知识入库时的增量索引与删除。
 */
@Service
public class LexicalIndexService {

    private static final Logger logger = LoggerFactory.getLogger(LexicalIndexService.class);

    private final LexicalIndexMapper lexicalIndexMapper;
    
    // 引入 Jieba 中文分词器
    private final JiebaSegmenter segmenter = new JiebaSegmenter();

    public LexicalIndexService(LexicalIndexMapper lexicalIndexMapper) {
        this.lexicalIndexMapper = lexicalIndexMapper;
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
            
            // 先删除旧记录
            lexicalIndexMapper.delete(new LambdaQueryWrapper<LexicalIndexDO>().eq(LexicalIndexDO::getDocId, doc.getId()));
            
            // 插入新记录
            LexicalIndexDO indexDO = new LexicalIndexDO();
            indexDO.setDocId(doc.getId());
            indexDO.setText(normalized);
            indexDO.setFilePath(filePath);
            indexDO.setKnowledgeTags(tags);
            indexDO.setSourceType(sourceType);
            indexDO.setCreatedAt(LocalDateTime.now());
            
            lexicalIndexMapper.insert(indexDO);
        }
    }

    public void removeByIds(List<String> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            return;
        }
        lexicalIndexMapper.delete(new LambdaQueryWrapper<LexicalIndexDO>().in(LexicalIndexDO::getDocId, docIds));
    }

    public List<Document> search(String query, int topK) {
        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty() || topK <= 0) {
            return List.of();
        }
        
        // 1. 数据库关键词匹配召回
        LambdaQueryWrapper<LexicalIndexDO> wrapper = new LambdaQueryWrapper<>();
        for (int i = 0; i < queryTokens.size(); i++) {
            if (i == 0) {
                wrapper.like(LexicalIndexDO::getText, queryTokens.get(i));
            } else {
                wrapper.or().like(LexicalIndexDO::getText, queryTokens.get(i));
            }
        }
        
        List<LexicalIndexDO> recalledDocs = lexicalIndexMapper.selectList(wrapper);
        if (recalledDocs.isEmpty()) {
            return List.of();
        }

        // 2. 在召回结果中计算 TF-IDF 评分
        int totalDocs = recalledDocs.size(); // 近似总文档数为召回数
        Map<String, Integer> docFreq = new HashMap<>();
        for (LexicalIndexDO record : recalledDocs) {
            Set<String> tokenSet = new HashSet<>(tokenize(record.getText()));
            for (String token : tokenSet) {
                docFreq.merge(token, 1, Integer::sum);
            }
        }

        List<ScoredRecord> scored = new ArrayList<>();
        for (LexicalIndexDO record : recalledDocs) {
            List<String> docTokens = tokenize(record.getText());
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
                    Document doc = new Document(item.record().getText());
                    doc.getMetadata().put("file_path", item.record().getFilePath());
                    doc.getMetadata().put("knowledge_tags", item.record().getKnowledgeTags());
                    doc.getMetadata().put("source_type", item.record().getSourceType() == null || item.record().getSourceType().isBlank() ? "obsidian" : item.record().getSourceType());
                    doc.getMetadata().put("lexical_score", item.score());
                    doc.getMetadata().put("doc_id", item.record().getDocId());
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

    private record ScoredRecord(LexicalIndexDO record, double score) {
    }
}
