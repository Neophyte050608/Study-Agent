package com.example.interview.agent;

import org.springframework.stereotype.Component;

/**
 * 决策层智能体（DecisionLayerAgent）。
 * 
 * 核心职责：根据当前面试的进度、用户的掌握程度、自适应难度状态以及历史画像信息，
 * 动态生成本轮的出题策略和侧重点。它是面试自适应流转的“大脑”。
 */
@Component
public class DecisionLayerAgent {

    /**
     * 生成当前轮次的决策计划。
     * 
     * @param topic 面试主题
     * @param difficultyLevel 当前自适应难度等级 (BASIC/NORMAL/ADVANCED)
     * @param followUpState 当前追问状态 (PROBE/DEEPEN/REMEDIATE 等)
     * @param topicMastery 用户对当前主题的掌握度评分 (0-100)
     * @param profileSnapshot 用户历史画像快照
     * @param answeredCount 已回答的题目数量
     * @return 包含策略提示和重点提示的决策计划
     */
    public DecisionPlan plan(String topic, String difficultyLevel, String followUpState, double topicMastery, String profileSnapshot, int answeredCount) {
        String safeTopic = topic == null ? "当前主题" : topic;
        String safeDifficulty = difficultyLevel == null ? "BASIC" : difficultyLevel;
        String safeFollowUp = followUpState == null ? "PROBE" : followUpState;
        String safeProfile = profileSnapshot == null ? "" : profileSnapshot;
        
        String strategy;
        // 1. 根据掌握度和追问状态决定基础策略
        if (topicMastery < 45 || "REMEDIATE".equalsIgnoreCase(safeFollowUp)) {
            strategy = "优先追问基础定义、关键机制与常见误区，要求回答结构清晰并覆盖必要边界。";
        } else if (topicMastery > 78 || "ADVANCE".equalsIgnoreCase(safeFollowUp) || "ADVANCED".equalsIgnoreCase(safeDifficulty)) {
            strategy = "优先场景化深挖，追问设计取舍、复杂度与故障处理，避免停留在概念复述。";
        } else {
            strategy = "保持中等强度追问，先验证核心原理，再要求给出工程落地示例。";
        }
        
        // 2. 结合历史画像进行针对性强化
        if (!safeProfile.isBlank()) {
            strategy = strategy + " 请必须查阅用户历史画像，**强制优先针对其历史薄弱点(Weaknesses)或低分技能进行追问和出题**。";
        }
        
        // 3. 构建当前轮次的聚焦重点
        String focus = "围绕" + safeTopic + "进行第" + (answeredCount + 1) + "题，当前难度为" + safeDifficulty + "。";
        
        return new DecisionPlan(strategy, focus);
    }

    /**
     * 决策计划结果类。
     * 
     * @param strategyHint 给大模型的策略提示词
     * @param focusHint 给大模型的聚焦重点提示词
     */
    public record DecisionPlan(
            String strategyHint,
            String focusHint
    ) {
    }
}
