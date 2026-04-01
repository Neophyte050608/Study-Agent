package com.example.interview.ingestion;

/**
 * 入库阶段节点的执行状态。
 *
 * <p>每个 {@link IngestionNodeStage} 在执行后都会产生一条 {@link IngestionNodeLog}，
 * 其中包含状态、耗时与输入输出计数，便于前端/运维侧观测与排障。</p>
 */
public enum IngestionNodeStatus {
    /**
     * 已跳过：阶段在本次运行中被主动略过（例如条件不满足、开关关闭等）。
     */
    SKIPPED,
    /**
     * 运行中：阶段正在执行（用于未来扩展异步/可视化进度场景）。
     */
    RUNNING,
    /**
     * 成功：阶段正常完成。
     */
    SUCCESS,
    /**
     * 失败：阶段执行抛出异常或返回不可恢复错误。
     */
    FAILED
}
