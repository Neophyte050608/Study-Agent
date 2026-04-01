package com.example.interview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.interview.config.RagRetrievalProperties;
import com.example.interview.entity.LexicalIndexDO;
import com.example.interview.mapper.LexicalIndexMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 词法索引服务。
 *
 * <p>职责说明：</p>
 * <p>1. 负责将切块后的文档文本持久化到 MySQL，提供精确术语检索能力。</p>
 * <p>2. 在召回结果上计算轻量 TF-IDF 分数，为 RAG 的 RRF 融合与重排提供词法通道输入。</p>
 * <p>3. 按配置选择 FULLTEXT 或 LIKE 检索模式，并在 AUTO 模式下优先使用 FULLTEXT、失败时回退 LIKE。</p>
 * <p>4. 统一复用共享分词服务，确保词法检索、意图定向检索与 RAG 重排使用同一套 token 口径。</p>
 */
@Service
public class LexicalIndexService {

    private static final Logger logger = LoggerFactory.getLogger(LexicalIndexService.class);
    private static final int DEFAULT_RECALL_LIMIT = 40;
    private static final int RECALL_LIMIT_MULTIPLIER = 8;

    private final LexicalIndexMapper lexicalIndexMapper;
    private final RetrievalTokenizerService retrievalTokenizerService;
    private final RagRetrievalProperties ragRetrievalProperties;

    public LexicalIndexService(
            LexicalIndexMapper lexicalIndexMapper,
            RetrievalTokenizerService retrievalTokenizerService,
            RagRetrievalProperties ragRetrievalProperties
    ) {
        this.lexicalIndexMapper = lexicalIndexMapper;
        this.retrievalTokenizerService = retrievalTokenizerService;
        this.ragRetrievalProperties = ragRetrievalProperties;
    }

    /**
     * 将最新文档内容写入词法索引表。
     *
     * @param documents 需要建立词法索引的文档列表
     */
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
            String parentId = metadata == null ? "" : String.valueOf(metadata.getOrDefault("parent_id", ""));
            String chunkStrategy = metadata == null ? "" : String.valueOf(metadata.getOrDefault("chunk_strategy", ""));
            Integer childIndex = parseChildIndex(metadata);
            String normalized = doc.getText().replaceAll("\\s+", " ").trim();

            // 先删后插，确保同一个 docId 在词法索引表里只有一份最新记录。
            lexicalIndexMapper.delete(new LambdaQueryWrapper<LexicalIndexDO>().eq(LexicalIndexDO::getDocId, doc.getId()));

            LexicalIndexDO indexDO = new LexicalIndexDO();
            indexDO.setDocId(doc.getId());
            indexDO.setText(normalized);
            indexDO.setFilePath(filePath);
            indexDO.setKnowledgeTags(tags);
            indexDO.setSourceType(sourceType);
            indexDO.setParentId(parentId);
            indexDO.setChildIndex(childIndex);
            indexDO.setChunkStrategy(chunkStrategy);
            indexDO.setCreatedAt(LocalDateTime.now());
            lexicalIndexMapper.insert(indexDO);
        }
    }

    /**
     * 按 docId 列表删除词法索引。
     *
     * @param docIds 文档 ID 列表
     */
    public void removeByIds(List<String> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            return;
        }
        lexicalIndexMapper.delete(new LambdaQueryWrapper<LexicalIndexDO>().in(LexicalIndexDO::getDocId, docIds));
    }

    /**
     * 执行通用词法检索。
     *
     * @param query 查询文本
     * @param topK 返回条数
     * @return 按 TF-IDF 近似分数排序后的文档列表
     */
    public List<Document> search(String query, int topK) {
        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty() || topK <= 0) {
            return List.of();
        }

        int recallLimit = resolveRecallLimit(topK);
        List<LexicalIndexDO> recalledDocs = recallByTextTokens(queryTokens, recallLimit);
        if (recalledDocs.isEmpty()) {
            return List.of();
        }

        return scoreRecords(queryTokens, recalledDocs).stream()
                .sorted(Comparator.comparingDouble(ScoredRecord::score).reversed())
                .limit(topK)
                .map(item -> toDocument(item.record(), item.score(), "lexical"))
                .collect(Collectors.toList());
    }

    /**
     * 执行带意图聚焦的词法检索。
     *
     * @param query 原始查询
     * @param focusTerms 从查询中抽取出的知识标签或路径聚焦词
     * @param topK 返回条数
     * @return 优先考虑标签与路径命中的结果
     */
    public List<Document> searchIntentDirected(String query, List<String> focusTerms, int topK) {
        if (topK <= 0) {
            return List.of();
        }
        List<String> queryTokens = tokenize(query);
        List<String> normalizedFocusTerms = normalizeTerms(focusTerms);
        if (queryTokens.isEmpty() && normalizedFocusTerms.isEmpty()) {
            return List.of();
        }

        int recallLimit = resolveRecallLimit(topK);
        List<String> textRecallTerms = normalizedFocusTerms.isEmpty() ? queryTokens : normalizedFocusTerms;
        List<LexicalIndexDO> recalledDocs = mergeRecords(
                recallByTextTokens(textRecallTerms, recallLimit),
                recallByFocusFieldsLike(normalizedFocusTerms, recallLimit)
        );
        if (recalledDocs.isEmpty()) {
            return normalizedFocusTerms.isEmpty() ? List.of() : search(query, topK);
        }

        List<ScoredRecord> tfIdfScored = scoreRecords(queryTokens, recalledDocs);
        if (tfIdfScored.isEmpty()) {
            return search(query, topK);
        }

        List<ScoredRecord> boosted = tfIdfScored.stream()
                .map(item -> new ScoredRecord(item.record(), item.score() + computeFocusBoost(item.record(), normalizedFocusTerms)))
                .filter(item -> item.score() > 0.0D)
                .collect(Collectors.toList());
        if (boosted.isEmpty()) {
            return search(query, topK);
        }

        return boosted.stream()
                .sorted(Comparator.comparingDouble(ScoredRecord::score).reversed())
                .limit(topK)
                .map(item -> toDocument(item.record(), item.score(), "intent_directed"))
                .collect(Collectors.toList());
    }

    /**
     * 统一输出 token 列表。
     *
     * @param text 原始文本
     * @return 共享分词服务产出的 token
     */
    public List<String> tokenize(String text) {
        return retrievalTokenizerService.tokenize(text);
    }

    /**
     * 根据配置选择 FULLTEXT 或 LIKE 的正文召回方式。
     *
     * @param textTokens 用于正文召回的 token 列表
     * @param limit 最大召回数量
     * @return 命中的索引记录
     */
    private List<LexicalIndexDO> recallByTextTokens(List<String> textTokens, int limit) {
        if (textTokens == null || textTokens.isEmpty()) {
            return List.of();
        }
        RagRetrievalProperties.LexicalSearchMode searchMode = ragRetrievalProperties.getLexicalSearchMode();
        return switch (searchMode) {
            case LIKE -> recallByLikeText(textTokens, limit);
            case FULLTEXT -> recallByFullText(textTokens, limit);
            case AUTO -> {
                try {
                    yield recallByFullText(textTokens, limit);
                } catch (RuntimeException e) {
                    // FULLTEXT 索引未建好或数据库不支持时自动降级，避免检索链路整体不可用。
                    logger.warn("FULLTEXT 召回失败，自动回退到 LIKE。原因: {}", e.getMessage());
                    yield recallByLikeText(textTokens, limit);
                }
            }
        };
    }

    /**
     * 使用 FULLTEXT 对正文进行召回。
     *
     * @param textTokens 正文检索 token
     * @param limit 最大召回数量
     * @return 命中的索引记录
     */
    private List<LexicalIndexDO> recallByFullText(List<String> textTokens, int limit) {
        String expression = buildFullTextExpression(textTokens);
        if (expression.isBlank()) {
            return List.of();
        }
        return lexicalIndexMapper.searchByFullText(expression, limit);
    }

    /**
     * 使用历史 LIKE 方案对正文进行召回。
     *
     * @param textTokens 正文检索 token
     * @param limit 最大召回数量
     * @return 命中的索引记录
     */
    private List<LexicalIndexDO> recallByLikeText(List<String> textTokens, int limit) {
        LambdaQueryWrapper<LexicalIndexDO> wrapper = new LambdaQueryWrapper<>();
        for (int i = 0; i < textTokens.size(); i++) {
            if (i == 0) {
                wrapper.like(LexicalIndexDO::getText, textTokens.get(i));
            } else {
                wrapper.or().like(LexicalIndexDO::getText, textTokens.get(i));
            }
        }
        wrapper.last("LIMIT " + limit);
        return lexicalIndexMapper.selectList(wrapper);
    }

    /**
     * 针对标签和路径字段执行轻量 LIKE 聚焦召回。
     *
     * @param focusTerms 聚焦词列表
     * @param limit 最大召回数量
     * @return 命中的索引记录
     */
    private List<LexicalIndexDO> recallByFocusFieldsLike(List<String> focusTerms, int limit) {
        if (focusTerms == null || focusTerms.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<LexicalIndexDO> wrapper = new LambdaQueryWrapper<>();
        for (int i = 0; i < focusTerms.size(); i++) {
            String term = focusTerms.get(i);
            if (i == 0) {
                wrapper.like(LexicalIndexDO::getKnowledgeTags, term)
                        .or().like(LexicalIndexDO::getFilePath, term);
            } else {
                wrapper.or().like(LexicalIndexDO::getKnowledgeTags, term)
                        .or().like(LexicalIndexDO::getFilePath, term);
            }
        }
        wrapper.last("LIMIT " + limit);
        return lexicalIndexMapper.selectList(wrapper);
    }

    /**
     * 将多路召回记录按 docId 去重合并，避免同一文档重复参与 TF-IDF 评分。
     *
     * @param first 第一批记录
     * @param second 第二批记录
     * @return 去重后的记录列表
     */
    private List<LexicalIndexDO> mergeRecords(List<LexicalIndexDO> first, List<LexicalIndexDO> second) {
        Map<String, LexicalIndexDO> merged = new LinkedHashMap<>();
        addRecords(merged, first);
        addRecords(merged, second);
        return new ArrayList<>(merged.values());
    }

    private void addRecords(Map<String, LexicalIndexDO> merged, List<LexicalIndexDO> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        for (LexicalIndexDO record : records) {
            if (record == null || record.getDocId() == null || record.getDocId().isBlank()) {
                continue;
            }
            merged.putIfAbsent(record.getDocId(), record);
        }
    }

    /**
     * 构造 MySQL FULLTEXT 检索表达式。
     *
     * @param textTokens 正文 token 列表
     * @return 适用于 MATCH ... AGAINST 的自然语言表达式
     */
    private String buildFullTextExpression(List<String> textTokens) {
        if (textTokens == null || textTokens.isEmpty()) {
            return "";
        }
        return textTokens.stream()
                .filter(item -> item != null && !item.isBlank())
                .collect(Collectors.collectingAndThen(Collectors.toCollection(LinkedHashSet::new), ArrayList::new))
                .stream()
                .collect(Collectors.joining(" "));
    }

    private int resolveRecallLimit(int topK) {
        return Math.max(topK * RECALL_LIMIT_MULTIPLIER, DEFAULT_RECALL_LIMIT);
    }

    private Integer parseChildIndex(Map<String, Object> metadata) {
        if (metadata == null || metadata.get("child_index") == null) {
            return null;
        }
        Object raw = metadata.get("child_index");
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (Exception ignored) {
            logger.debug("无法解析 child_index：{}", raw);
            return null;
        }
    }

    /**
     * 对当前召回结果执行轻量 TF-IDF 评分。
     *
     * @param queryTokens 查询 token
     * @param recalledDocs 已召回记录
     * @return 带分数的记录列表
     */
    private List<ScoredRecord> scoreRecords(List<String> queryTokens, List<LexicalIndexDO> recalledDocs) {
        int totalDocs = recalledDocs.size();
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
            double score = 0.0D;
            for (String token : queryTokens) {
                long termFreq = tf.getOrDefault(token, 0L);
                if (termFreq == 0L) {
                    continue;
                }
                int df = docFreq.getOrDefault(token, 0);
                double idf = Math.log(1.0D + (double) (totalDocs + 1) / (df + 1));
                score += termFreq * idf;
            }
            if (score > 0.0D) {
                scored.add(new ScoredRecord(record, score));
            }
        }
        return scored;
    }

    /**
     * 为意图聚焦检索增加额外加权。
     *
     * @param record 当前记录
     * @param normalizedFocusTerms 聚焦词列表
     * @return 额外加分
     */
    private double computeFocusBoost(LexicalIndexDO record, List<String> normalizedFocusTerms) {
        String tags = record.getKnowledgeTags() == null ? "" : record.getKnowledgeTags().toLowerCase(Locale.ROOT);
        String path = record.getFilePath() == null ? "" : record.getFilePath().toLowerCase(Locale.ROOT);
        double focusBoost = 0.0D;
        for (String term : normalizedFocusTerms) {
            if (tags.contains(term)) {
                focusBoost += 1.2D;
            }
            if (path.contains(term)) {
                focusBoost += 0.8D;
            }
        }
        return focusBoost;
    }

    private List<String> normalizeTerms(List<String> terms) {
        if (terms == null || terms.isEmpty()) {
            return List.of();
        }
        return terms.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(item -> item.toLowerCase(Locale.ROOT).trim())
                .filter(item -> item.length() >= 2)
                .collect(Collectors.collectingAndThen(Collectors.toCollection(LinkedHashSet::new), ArrayList::new));
    }

    private Document toDocument(LexicalIndexDO record, double score, String fallbackSourceType) {
        Document doc = new Document(record.getText());
        doc.getMetadata().put("file_path", record.getFilePath());
        doc.getMetadata().put("knowledge_tags", record.getKnowledgeTags());
        String sourceType = record.getSourceType();
        doc.getMetadata().put("source_type", sourceType == null || sourceType.isBlank() ? fallbackSourceType : sourceType);
        doc.getMetadata().put("lexical_score", score);
        doc.getMetadata().put("doc_id", record.getDocId());
        if (record.getParentId() != null) {
            doc.getMetadata().put("parent_id", record.getParentId());
        }
        if (record.getChildIndex() != null) {
            doc.getMetadata().put("child_index", record.getChildIndex());
        }
        if (record.getChunkStrategy() != null) {
            doc.getMetadata().put("chunk_strategy", record.getChunkStrategy());
        }
        return doc;
    }

    private record ScoredRecord(LexicalIndexDO record, double score) {
    }
}
