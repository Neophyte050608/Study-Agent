package com.example.interview.graph;

/**
 * GraphRAG 关联概念的轻量投影视图。
 *
 * <p>职责说明：</p>
 * <p>1. 只暴露检索拼装阶段真正需要的字段，避免为了构造上下文而加载完整图节点。</p>
 * <p>2. 为 `RAGService` 提供统一的 `名称 + 描述 + 类型` 读取口径。</p>
 * <p>3. 让仓储查询可以直接返回“可用于证据拼接”的结构，而不是仅返回概念名字符串。</p>
 */
public interface TechConceptSnippetView extends CharSequence {

    /**
     * @return 图谱概念名称
     */
    String getName();

    /**
     * @return 图谱概念描述；若为空则由调用方决定如何降级展示
     */
    String getDescription();

    /**
     * @return 图谱概念类型，例如 Framework、Database、Concept
     */
    String getType();

    /**
     * 兼容历史 GraphRAG 旧拼装路径，使投影视图也能被当作概念名字符序列处理。
     *
     * @return 概念名称长度；为空时返回 0
     */
    @Override
    default int length() {
        return getName() == null ? 0 : getName().length();
    }

    /**
     * 兼容 `StringBuilder/String.join` 对 `CharSequence` 的读取。
     *
     * @param index 字符下标
     * @return 指定位置的字符
     */
    @Override
    default char charAt(int index) {
        return (getName() == null ? "" : getName()).charAt(index);
    }

    /**
     * 返回概念名的子序列，供兼容路径安全读取。
     *
     * @param start 起始下标
     * @param end 结束下标
     * @return 概念名的子串视图
     */
    @Override
    default CharSequence subSequence(int start, int end) {
        return (getName() == null ? "" : getName()).subSequence(start, end);
    }
}
