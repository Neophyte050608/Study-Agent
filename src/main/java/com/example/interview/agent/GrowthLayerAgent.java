package com.example.interview.agent;

import org.springframework.stereotype.Component;

/**
 * 成长层智能体（GrowthLayerAgent）。
 * 
 * 核心职责：
 * 1. 负责将底层的、偏向模型评估的单轮反馈，加工成用户更易理解、更具指导性的成长反馈。
 * 2. 结合决策层的策略轨迹和知识层的证据来源，增强反馈的透明度和权威性。
 * 3. 在面试结束阶段，根据全场表现和平均分，对下一阶段的训练重点给出修正和精炼建议。
 */
@Component
public class GrowthLayerAgent {

    /**
     * 组装单轮面试的成长反馈。
     * 
     * @param feedback 评估层产出的原始评价
     * @param decisionPlan 决策层的策略计划
     * @param trace 评估过程中的执行追踪信息
     * @return 包含评估、策略说明和知识来源的综合反馈文本
     */
    public String composeRoundFeedback(String feedback, DecisionLayerAgent.DecisionPlan decisionPlan, EvaluationAgent.LayerTrace trace) {
        String safeFeedback = feedback == null ? "" : feedback.trim();
        String strategy = decisionPlan == null ? "" : decisionPlan.strategyHint();
        String focus = decisionPlan == null ? "" : decisionPlan.focusHint();
        String source = trace != null && trace.webFallbackUsed() ? "网络补充" : "本地知识优先";
        String retrieval = trace == null ? "0" : String.valueOf(trace.retrievedCount());
        
        return safeFeedback +
                "\n本轮策略：" + strategy +
                "\n本轮聚焦：" + focus +
                "\n知识来源：" + source + "（命中文档数：" + retrieval + "）";
    }

    /**
     * 精炼并修正下一阶段的学习/面试重点建议。
     * 
     * @param nextFocus 评估层根据当前表现给出的下一步建议
     * @param targetedSuggestion 基于用户画像计算出的目标建议
     * @param averageScore 全场平均分
     * @return 最终的针对性学习重点建议
     */
    public String refineNextFocus(String nextFocus, String targetedSuggestion, double averageScore) {
        String base = (nextFocus == null || nextFocus.isBlank()) ? targetedSuggestion : nextFocus;
        String safeBase = base == null ? "" : base.trim();
        
        // 根据最终得分动态调整成长建议的强度
        if (averageScore >= 85) {
            return safeBase + "\n- 增加跨场景追问与设计取舍题。";
        }
        if (averageScore < 60) {
            return safeBase + "\n- 先做基础概念与边界条件专项复练。";
        }
        return safeBase + "\n- 继续保持原理与实战结合训练。";
    }
}
