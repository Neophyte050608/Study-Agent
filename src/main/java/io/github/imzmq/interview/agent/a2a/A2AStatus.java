package io.github.imzmq.interview.agent.a2a;

/**
 * A2A (Agent-to-Agent) 任务或消息的处理状态枚举。
 * 用于在分布式智能体系统中统一监控和观测任务的执行进度。
 */
public enum A2AStatus {
    /** 任务已创建，等待处理 */
    PENDING,
    
    /** 任务正在处理中 */
    PROCESSING,
    
    /** 任务已成功完成 */
    DONE,
    
    /** 任务处理失败，可能包含错误信息 */
    FAILED
}

