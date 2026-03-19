package com.example.interview.agent;

import org.springframework.stereotype.Component;

/**
 * 决策层智能体。
 * 根据题目进度、掌握度与画像信息生成本轮出题策略。
 */
@Component
public class DecisionLayerAgent {

    public DecisionPlan plan(String topic, String difficultyLevel, String followUpState, double topicMastery, String profileSnapshot, int answeredCount) {
        String safeTopic = topic == null ? "当前主题" : topic;
        String safeDifficulty = difficultyLevel == null ? "BASIC" : difficultyLevel;
        String safeFollowUp = followUpState == null ? "PROBE" : followUpState;
        String safeProfile = profileSnapshot == null ? "" : profileSnapshot;
        String strategy;
        if (topicMastery < 45 || "REMEDIATE".equalsIgnoreCase(safeFollowUp)) {
            strategy = "优先追问基础定义、关键机制与常见误区，要求回答结构清晰并覆盖必要边界。";
        } else if (topicMastery > 78 || "ADVANCE".equalsIgnoreCase(safeFollowUp) || "ADVANCED".equalsIgnoreCase(safeDifficulty)) {
            strategy = "优先场景化深挖，追问设计取舍、复杂度与故障处理，避免停留在概念复述。";
        } else {
            strategy = "保持中等强度追问，先验证核心原理，再要求给出工程落地示例。";
        }
        if (!safeProfile.isBlank() && safeProfile.contains("薄弱")) {
            strategy = strategy + " 结合历史薄弱点优先出题。";
        }
        String focus = "围绕" + safeTopic + "进行第" + (answeredCount + 1) + "题，当前难度为" + safeDifficulty + "。";
        return new DecisionPlan(strategy, focus);
    }

    public record DecisionPlan(
            String strategyHint,
            String focusHint
    ) {
    }
}
