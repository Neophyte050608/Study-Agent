package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import java.util.List;
import java.util.Map;

/**
 * 单条 chunk 级黄金评测用例，对应通用查询集 JSONL 的一行记录。
 *
 * <p>与旧的 {@code EvalCase(query, expectedKeywords, tag)} 关键词口径不同，此处保留
 * chunk_id 级 ground truth 与分级 relevance，用于计算 HitRate@K / NDCG@K / Recall@K
 * 等按 chunk_id 精确匹配的检索指标。</p>
 *
 * @param queryId         题目 ID，例如 {@code q001}
 * @param question        自然语言问题
 * @param groundTruth     命中的 chunk_id 有序列表
 * @param relevance       chunk_id 到相关性分级(通常 1/2/3)的映射；缺失的 chunk 视为 0
 * @param sourceArticleId gold 来源文章 ID，便于分析同源偏差
 * @param sourceUrl       来源 URL
 * @param category        分类标签
 */
public record GoldSetCase(
        String queryId,
        String question,
        List<String> groundTruth,
        Map<String, Integer> relevance,
        String sourceArticleId,
        String sourceUrl,
        String category
) {
    /**
     * 返回指定 chunk 的相关性分级，缺失时为 0。
     *
     * @param chunkId chunk 标识
     * @return 相关性分级，范围通常为 0..3
     */
    public int relevanceOf(String chunkId) {
        if (chunkId == null || relevance == null) {
            return 0;
        }
        Integer value = relevance.get(chunkId);
        return value == null ? 0 : value;
    }

    /**
     * 判断某个 chunk 是否属于该题的 ground truth。
     *
     * @param chunkId chunk 标识
     * @return 命中返回 true
     */
    public boolean isRelevant(String chunkId) {
        return chunkId != null && groundTruth != null && groundTruth.contains(chunkId);
    }
}
