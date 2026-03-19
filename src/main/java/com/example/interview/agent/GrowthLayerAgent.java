package com.example.interview.agent;

import org.springframework.stereotype.Component;

/**
 * 成长层智能体。
 * 负责把单轮评估转成可执行反馈，并在终局报告中给出训练重点建议。
 */
@Component
public class GrowthLayerAgent {

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

    public String refineNextFocus(String nextFocus, String targetedSuggestion, double averageScore) {
        String base = (nextFocus == null || nextFocus.isBlank()) ? targetedSuggestion : nextFocus;
        String safeBase = base == null ? "" : base.trim();
        if (averageScore >= 85) {
            return safeBase + "\n- 增加跨场景追问与设计取舍题。";
        }
        if (averageScore < 60) {
            return safeBase + "\n- 先做基础概念与边界条件专项复练。";
        }
        return safeBase + "\n- 继续保持原理与实战结合训练。";
    }
}
