package io.github.imzmq.interview.interview.domain;

/**
 * 面试 SOP 状态机枚举。
 * 代表一场结构化面试的标准作业程序（Standard Operating Procedure）环节。
 */
public enum InterviewStage {
    /** 阶段 1：开场白与自我介绍 */
    INTRODUCTION("自我介绍"),
    
    /** 阶段 2：基于简历的项目经历深挖 */
    RESUME_DEEP_DIVE("项目经历与难点挖掘"),
    
    /** 阶段 3：核心专业技能（八股/原理）考核 */
    CORE_KNOWLEDGE("核心专业技能考察"),
    
    /** 阶段 4：场景题或算法手撕 */
    SCENARIO_OR_CODING("系统设计与实战编码"),
    
    /** 阶段 5：面试收尾与候选人反问 */
    CLOSING("反问环节");

    private final String description;

    InterviewStage(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 状态机流转：获取下一个正常环节
     */
    public InterviewStage next() {
        int nextOrdinal = this.ordinal() + 1;
        if (nextOrdinal < values().length) {
            return values()[nextOrdinal];
        }
        return this; // 已经是最后一个环节
    }
}



