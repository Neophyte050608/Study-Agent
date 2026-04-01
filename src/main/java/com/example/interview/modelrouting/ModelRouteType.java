package com.example.interview.modelrouting;

/**
 * 模型路由任务类型。
 * 用于区分普通对话还是需要深度推理的任务，进而选择不同能力（和成本）的底层模型。
 */
public enum ModelRouteType {
    /**
     * 普通通用型任务（如意图识别、总结、闲聊等），优先使用速度快、成本低的 defaultModel。
     */
    GENERAL,
    
    /**
     * 深度推理型任务（如代码评估、复杂面试打分等），优先使用带有 CoT (Chain of Thought) 能力的 deepThinkingModel。
     */
    THINKING
}
