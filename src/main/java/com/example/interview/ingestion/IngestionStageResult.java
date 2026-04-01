package com.example.interview.ingestion;

import java.util.Map;

/**
 * 入库阶段节点的执行结果。
 *
 * <p>该对象用于记录阶段的“输入/输出规模”以及可被 UI 解析的结构化详情。
 * 例如 PARSE 阶段的 details 可包含 parsedDocuments，CHUNK 阶段可包含 chunks。</p>
 *
 * @param inputCount  阶段输入规模
 * @param outputCount 阶段输出规模
 * @param message     简要阶段消息
 * @param details     结构化详情（用于 UI 展示或统计聚合）
 */
public record IngestionStageResult(
        int inputCount,
        int outputCount,
        String message,
        Map<String, Object> details
) {
    /**
     * 创建一个不包含 details 的简化结果。
     *
     * @param inputCount  阶段输入规模
     * @param outputCount 阶段输出规模
     * @param message     阶段消息
     * @return 阶段结果
     */
    public static IngestionStageResult of(int inputCount, int outputCount, String message) {
        return new IngestionStageResult(inputCount, outputCount, message, Map.of());
    }
}
