package io.github.imzmq.interview.knowledge.domain;

import java.util.Locale;

/**
 * 统一知识检索模式。
 *
 * <p>当前仅保留三种模式：纯 RAG、纯本地图谱、融合检索。</p>
 */
public enum KnowledgeRetrievalMode {
    RAG_ONLY,
    LOCAL_GRAPH_ONLY,
    HYBRID_FUSION;

    public static KnowledgeRetrievalMode fromNullable(String value, KnowledgeRetrievalMode fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        try {
            return KnowledgeRetrievalMode.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}




