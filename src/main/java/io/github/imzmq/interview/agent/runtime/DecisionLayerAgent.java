package io.github.imzmq.interview.agent.runtime;

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
    public DecisionPlan plan(String topic, String difficultyLevel, String followUpState, double topicMastery, String profileSnapshot, int answeredCount, io.github.imzmq.interview.interview.domain.InterviewStage currentStage, io.github.imzmq.interview.interview.domain.InterviewStage nextStage) {
        String safeTopic = topic == null ? "当前主题" : topic;
        String safeDifficulty = difficultyLevel == null ? "BASIC" : difficultyLevel;
        String safeFollowUp = followUpState == null ? "PROBE" : followUpState;
        String safeProfile = profileSnapshot == null ? "" : profileSnapshot;
        
        StringBuilder strategy = new StringBuilder();
        
        // 1. 评估当前回答的策略
        strategy.append("【评估策略】：");
        if (topicMastery < 45 || "REMEDIATE".equalsIgnoreCase(safeFollowUp)) {
            strategy.append("当前掌握度较低或处于补救追问状态。请优先关注候选人对基础定义、关键机制的理解，指出其明显误区。");
        } else if (topicMastery > 78 || "ADVANCE".equalsIgnoreCase(safeFollowUp) || "ADVANCED".equalsIgnoreCase(safeDifficulty)) {
            strategy.append("当前处于高级深挖状态。请严格审视候选人对设计取舍、复杂度与故障处理的回答，避免给空泛的背诵打高分。");
        } else {
            strategy.append("保持中等强度评估，兼顾理论与实践。");
        }
        
        // 2. 结合历史画像进行针对性强化
        if (!safeProfile.isBlank()) {
            strategy.append(" 评估和追问时，请务必参考历史画像中的薄弱点(Weaknesses)。");
        }

        // 3. 生成下一题的 SOP 策略
        strategy.append("\n【出题策略】：当前处于【").append(currentStage != null ? currentStage.getDescription() : "未知环节").append("】阶段。");
        if (nextStage != null && currentStage != nextStage) {
            strategy.append("注意：根据面试进度，下一题将**进入全新的【").append(nextStage.getDescription()).append("】阶段**！");
        }
        
        String targetStageName = nextStage != null ? nextStage.name() : (currentStage != null ? currentStage.name() : "");
        if ("INTRODUCTION".equals(targetStageName)) {
            strategy.append("请礼貌回应自我介绍，并基于简历内容或常见破冰话题生成一个轻松的切入问题。");
        } else if ("RESUME_DEEP_DIVE".equals(targetStageName)) {
            strategy.append("请务必结合用户的项目经验，针对实际项目难点、系统设计取舍、高并发场景等进行深挖。");
        } else if ("CORE_KNOWLEDGE".equals(targetStageName)) {
            strategy.append("重点考察核心专业技能（如八股文、底层原理、框架源码），检验技术深度。");
        } else if ("SCENARIO_OR_CODING".equals(targetStageName)) {
            strategy.append("请给出一个具体的业务场景设计题或算法手撕题，要求给出实现思路或伪代码。");
        } else if ("CLOSING".equals(targetStageName)) {
            strategy.append("面试已接近尾声，请进行简短收尾，并询问候选人是否有关于团队、业务或技术栈的反问。");
        }
        
        // 4. 构建当前轮次的聚焦重点
        String focus = "围绕" + safeTopic + "进行第" + (answeredCount + 1) + "题，当前难度为" + safeDifficulty + "。";
        
        return new DecisionPlan(strategy.toString(), focus);
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




