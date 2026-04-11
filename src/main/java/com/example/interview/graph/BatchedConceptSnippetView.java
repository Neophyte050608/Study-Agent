package com.example.interview.graph;

/**
 * 批量 GraphRAG 查询结果的投影视图。
 * 包含 anchor 字段用于将结果按查询锚点分组。
 */
public interface BatchedConceptSnippetView extends TechConceptSnippetView {

    /**
     * @return 查询锚点（原始 token）
     */
    String getAnchor();
}
