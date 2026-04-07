package com.example.interview.service;

import java.util.Locale;

/**
 * 统一知识检索模式。
 *
 * <p>第一阶段只完成模式壳层接入，实际仍由 RAG 承载检索。
 * 待本地知识图链路落地后，再逐步接入 LOCAL_GRAPH_* 与 HYBRID_FUSION 的真实执行逻辑。</p>
 */
public enum KnowledgeRetrievalMode {
    RAG_ONLY,
    LOCAL_GRAPH_ONLY,
    LOCAL_GRAPH_FIRST,
    HYBRID_FUSION;

    public static KnowledgeRetrievalMode fromNullable(String value, KnowledgeRetrievalMode fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return KnowledgeRetrievalMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
