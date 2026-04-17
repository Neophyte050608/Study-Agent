package io.github.imzmq.interview.ingestion.pipeline;

/**
 * 入库阶段节点接口。
 *
 * <p>每个阶段对应一个节点实现，用于在任务化入库管道中执行该阶段的逻辑并返回输入输出计数与结构化详情。
 * 当前实现以“阶段注册表”方式组织（见 {@link IngestionStageNodeRegistry}），便于后续替换为更可插拔的管道节点实现。</p>
 */
public interface IngestionStageNode {

    /**
     * 返回该节点对应的阶段枚举。
     *
     * @return 阶段类型
     */
    IngestionNodeStage stage();

    /**
     * 执行阶段逻辑。
     *
     * <p>节点实现可从 {@link IngestionExecutionContext} 读取懒加载计数、同步执行器等依赖，并将阶段结果封装为 {@link IngestionStageResult}。</p>
     *
     * @param context 执行上下文（包含计数供应器、同步提交器、当前统计等）
     * @return 阶段执行结果（输入输出计数、消息与详情）
     */
    IngestionStageResult execute(IngestionExecutionContext context);
}

