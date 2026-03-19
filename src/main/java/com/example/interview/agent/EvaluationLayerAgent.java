package com.example.interview.agent;

import com.example.interview.service.RAGService;
import org.springframework.stereotype.Component;

/**
 * 评估层智能体。
 * 将决策层策略与知识层证据注入评分模型，产出结构化评估结果。
 */
@Component
public class EvaluationLayerAgent {

    private final EvaluationAgent evaluationAgent;

    public EvaluationLayerAgent(EvaluationAgent evaluationAgent) {
        this.evaluationAgent = evaluationAgent;
    }

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
        return evaluationAgent.evaluateAnswerWithKnowledge(topic, question, userAnswer, difficultyLevel, followUpState, topicMastery, profileSnapshot, strategy, packet);
    }
}
