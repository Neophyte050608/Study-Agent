package io.github.imzmq.interview.agent.core;

/**
 * 智能体（Agent）的基础接口定义。
 *
 * @param <I> 输入参数类型
 * @param <O> 输出结果类型
 * @deprecated 该接口未提供运行时抽象价值，仅被两个类实现，计划在后续清理中移除。
 */
@Deprecated
public interface Agent<I, O> {
    /**
     * 执行智能体逻辑。
     * 
     * @param input 输入请求
     * @return 智能体处理后的响应
     */
    O execute(I input);
}

