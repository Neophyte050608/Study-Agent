package io.github.imzmq.interview.ingestion.application;

import io.github.imzmq.interview.ingestion.application.IngestionService;

/**
 * 入库任务的执行返回结果（用于控制层快速回包）。
 *
 * <p>该对象面向调用方：包含任务 ID、任务状态以及本次入库的摘要信息（扫描/新增/修改/删除/失败等）。</p>
 *
 * @param taskId  本次入库任务的唯一标识
 * @param status  任务状态（SUCCESS/PARTIAL_SUCCESS/FAILED 等）
 * @param summary 入库摘要（来自 {@link IngestionService} 的同步结果）
 */
public record IngestionTaskExecutionResult(
        String taskId,
        IngestionTaskStatus status,
        IngestionService.SyncSummary summary
) {
}


