package com.example.interview.core.statemachine;

/**
 * 触发面试状态流转的事件枚举
 */
public enum InterviewEvent {
    /** 候选人完成了自我介绍 */
    FINISH_INTRO,
    
    /** 简历深挖阶段达到评估阈值或时间 */
    COMPLETE_RESUME_DIVE,
    
    /** 核心八股考察完毕 */
    COMPLETE_CORE_KNOWLEDGE,
    
    /** 算法题/场景题完成 */
    COMPLETE_CODING
}
