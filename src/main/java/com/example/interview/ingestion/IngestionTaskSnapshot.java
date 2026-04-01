package com.example.interview.ingestion;

import java.util.List;
import java.util.Map;

/**
 * 入库任务快照（内存态留档）。
 *
 * <p>该快照用于在不依赖外部持久化的情况下，保留最近若干次入库任务的执行结果与节点日志，
 * 便于前端查询/观测统计；当前由 {@link IngestionTaskService} 以最近优先队列维护。</p>
 *
 * @param taskId       任务 ID
 * @param pipelineName 管道名称
 * @param sourceType   数据源类型（LOCAL_VAULT / BROWSER_UPLOAD）
 * @param status       任务状态
 * @param startedAt    任务开始时间戳（毫秒）
 * @param endedAt      任务结束时间戳（毫秒）
 * @param summary      入库摘要（可直接展示给用户的统计字段）
 * @param nodeLogs     阶段节点日志（用于回放与聚合统计）
 * @param errorMessage 失败时的错误信息（成功时可为 null）
 */
public record IngestionTaskSnapshot(
        String taskId,
        String pipelineName,
        String sourceType,
        IngestionTaskStatus status,
        long startedAt,
        long endedAt,
        Map<String, Object> summary,
        List<IngestionNodeLog> nodeLogs,
        String errorMessage
) {
}
