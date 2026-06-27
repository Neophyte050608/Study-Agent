package io.github.imzmq.interview.knowledge.application.retrieval;

/**
 * 结构化查询改写结果。
 *
 * <p>coreTerms 用于高精度检索通道，fullQuery 用于语义向量检索。</p>
 */
public record RewrittenQuery(String coreTerms, String expandTerms, String fullQuery) {
    public static RewrittenQuery fallback(String raw) {
        return new RewrittenQuery(raw == null ? "" : raw, "", raw == null ? "" : raw);
    }
}
