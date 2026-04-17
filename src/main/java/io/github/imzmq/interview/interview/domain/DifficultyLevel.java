package io.github.imzmq.interview.interview.domain;

/**
 * 面试难度等级枚举。
 * 定义了 BASIC (基础), INTERMEDIATE (中级), ADVANCED (高级) 三个档位。
 */
public enum DifficultyLevel {
    /** 基础级：侧重核心概念与 API 使用 */
    BASIC,
    /** 中级：侧重原理、机制与常见场景应用 */
    INTERMEDIATE,
    /** 高级：侧重底层源码、架构设计与高并发/高性能优化 */
    ADVANCED;

    /**
     * 提升难度等级。
     * @return 提升后的难度，若已是最高级则返回 ADVANCED
     */
    public DifficultyLevel harder() {
        if (this == BASIC) {
            return INTERMEDIATE;
        }
        if (this == INTERMEDIATE) {
            return ADVANCED;
        }
        return ADVANCED;
    }

    /**
     * 降低难度等级。
     * @return 降低后的难度，若已是最低级则返回 BASIC
     */
    public DifficultyLevel easier() {
        if (this == ADVANCED) {
            return INTERMEDIATE;
        }
        if (this == INTERMEDIATE) {
            return BASIC;
        }
        return BASIC;
    }
}




