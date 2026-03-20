package com.example.interview.agent;

import com.example.interview.service.RAGService;
import org.springframework.stereotype.Component;

/**
 * 评估层智能体（EvaluationLayerAgent）。
 * 
 * 核心职责：将决策层（DecisionLayer）生成的出题/评估策略，
 * 与知识层（KnowledgeLayer）检索到的专业证据包（KnowledgePacket）进行融合，
 * 并驱动底层的评估执行器（EvaluationAgent）产出最终的结构化评估结果。
 */
@Component
public class EvaluationLayerAgent {

    private final EvaluationAgent evaluationAgent;

    public EvaluationLayerAgent(EvaluationAgent evaluationAgent) {
        this.evaluationAgent = evaluationAgent;
    }

    /**
     * 执行四层架构下的综合评估。
     * 
     * @param topic 面试主题
     * @param question 当前题目
     * @param userAnswer 用户回答
     * @param difficultyLevel 当前自适应难度等级
     * @param followUpState 当前追问状态
     * @param topicMastery 用户对该主题的掌握度
     * @param profileSnapshot 用户画像快照
     * @param decisionPlan 决策层产出的计划（包含策略提示）
     * @param packet 知识层检索到的证据包
     * @return 包含评分、反馈及下一题建议的评估结果
     */
    public EvaluationAgent.LayeredEvaluation evaluate(
            String topic,
            String question,
            String userAnswer,
            String difficultyLevel,
            String followUpState,
            double topicMastery,
            String profileSnapshot,
            DecisionLayerAgent.DecisionPlan decisionPlan,
            RAGService.KnowledgePacket packet
    ) {
        String strategy = decisionPlan == null ? "" : decisionPlan.strategyHint();
        // 委托给 evaluationAgent 执行具体的 LLM 交互和结果解析
        return evaluationAgent.evaluateAnswerWithKnowledge(topic, question, userAnswer, difficultyLevel, followUpState, topicMastery, profileSnapshot, strategy, packet);
    }
}
