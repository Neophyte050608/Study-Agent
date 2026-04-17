package io.github.imzmq.interview.modelrouting.core;

/**
 * 模型熔断器状态枚举。
 */
public enum ModelCircuitState {
    /**
     * 闭合状态：正常提供服务，请求会被直接放行给模型。
     */
    CLOSED,
    
    /**
     * 断开状态：模型调用失败次数达到阈值，请求会被直接拒绝（熔断），触发降级到下一个候选模型。
     */
    OPEN,
    
    /**
     * 半开状态：熔断冷却时间结束后，允许放行少量探活请求，以测试模型是否恢复健康。
     */
    HALF_OPEN
}

