package com.example.interview.ingestion;

/**
 * 入库任务状态。
 *
 * <p>与 {@link IngestionNodeStatus} 不同：该枚举描述的是“任务整体”的状态，
 * 可由同步摘要（例如 failedFiles）与异常情况综合计算得出。</p>
 */
public enum IngestionTaskStatus {
    /**
     * 待执行：任务已创建但尚未开始（目前主要用于扩展预留）。
     */
    PENDING,
    /**
     * 执行中：任务正在运行（目前主要用于扩展预留）。
     */
    RUNNING,
    /**
     * 全量成功：未出现失败文件/阶段。
     */
    SUCCESS,
    /**
     * 部分成功：存在失败文件，但整体仍产出了部分有效结果（例如部分文件入库成功）。
     */
    PARTIAL_SUCCESS,
    /**
     * 失败：任务执行抛出异常或无法生成有效同步摘要。
     */
    FAILED
}
