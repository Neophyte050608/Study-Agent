package com.example.interview.core;

/**
 * 面试追问状态枚举。
 * 用于决定 AI 在用户回答后的下一步策略。
 */
public enum FollowUpState {
    /** 补救：用户回答较差，需要引导或换一个更简单的问题 */
    REMEDIATE,
    /** 探查：用户回答尚可，继续深入挖掘该知识点 */
    PROBE,
    /** 进阶：用户回答很好，可以进入更高难度或下一个主题 */
    ADVANCE;

    /**
     * 根据单题得分决定追问状态。
     * 
     * @param score 0-100 的得分
     * @return 对应的追问状态
     */
    public static FollowUpState byScore(int score) {
        if (score < 60) {
            return REMEDIATE;
        }
        if (score >= 85) {
            return ADVANCE;
        }
        return PROBE;
    }
}
