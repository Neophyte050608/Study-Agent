package io.github.imzmq.interview.ingestion.pipeline;

import java.util.List;

/**
 * 入库管道定义（面向运行时的不可变视图）。
 *
 * <p>该对象通常由 {@link IngestionPipelineRegistry} 从 {@link IngestionPipelineProperties} 的配置解析得到，
 * 并在 {@link IngestionTaskService} 中按 stages 顺序逐阶段执行。</p>
 *
 * @param name      管道名称（用于观测与展示）
 * @param sourceType 数据源类型（如 LOCAL_VAULT / BROWSER_UPLOAD）
 * @param enabled   是否启用；禁用时不允许执行该管道
 * @param stages    阶段列表，按顺序串行执行
 */
public record IngestionPipelineDefinition(
        String name,
        String sourceType,
        boolean enabled,
        List<IngestionNodeStage> stages
) {
}

