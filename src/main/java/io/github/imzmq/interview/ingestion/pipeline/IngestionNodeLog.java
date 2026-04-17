package io.github.imzmq.interview.ingestion.pipeline;

import java.util.Map;

/**
 * 入库任务的“阶段节点”执行日志。
 *
 * <p>该日志用于在任务维度上还原一次入库的执行轨迹：每个阶段开始/结束时间、输入输出规模以及可解析的结构化详情。
 * 前端展示的 stageOverview/hotStages 统计也基于该日志聚合得出。</p>
 *
 * @param stage      阶段枚举（如 FETCH/PARSE/CHUNK 等）
 * @param status     阶段执行状态
 * @param startedAt  阶段开始时间戳（毫秒）
 * @param endedAt    阶段结束时间戳（毫秒）
 * @param inputCount 阶段输入规模（例如“进入该阶段时的文档/块数量”）
 * @param outputCount 阶段输出规模（例如“阶段处理后的文档/块数量”）
 * @param message    简要消息，便于人类快速扫读（非强结构化字段）
 * @param details    结构化详情（可用于 UI 或告警系统解析展示）
 */
public record IngestionNodeLog(
        IngestionNodeStage stage,
        IngestionNodeStatus status,
        long startedAt,
        long endedAt,
        int inputCount,
        int outputCount,
        String message,
        Map<String, Object> details
) {
}

