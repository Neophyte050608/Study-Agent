package com.example.interview.agent;

import com.example.interview.core.InterviewSession;
import com.example.interview.core.Question;
import com.example.interview.rag.ResumeLoader;
import com.example.interview.service.InterviewLearningProfileService;
import com.example.interview.service.RAGService;
import com.example.interview.session.SessionRepository;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 面试编排中枢。
 * 串联四层智能体（决策/知识/评估/成长）并维护会话状态。
 */
@Component
public class InterviewOrchestratorAgent {

    private final EvaluationAgent evaluationAgent;
    private final KnowledgeLayerAgent knowledgeLayerAgent;
    private final DecisionLayerAgent decisionLayerAgent;
    private final EvaluationLayerAgent evaluationLayerAgent;
    private final GrowthLayerAgent growthLayerAgent;
    private final ResumeLoader resumeLoader;
    private final SessionRepository sessionRepository;
    private final InterviewLearningProfileService learningProfileService;

    public InterviewOrchestratorAgent(EvaluationAgent evaluationAgent, KnowledgeLayerAgent knowledgeLayerAgent, DecisionLayerAgent decisionLayerAgent, EvaluationLayerAgent evaluationLayerAgent, GrowthLayerAgent growthLayerAgent, ResumeLoader resumeLoader, SessionRepository sessionRepository, InterviewLearningProfileService learningProfileService) {
        this.evaluationAgent = evaluationAgent;
        this.knowledgeLayerAgent = knowledgeLayerAgent;
        this.decisionLayerAgent = decisionLayerAgent;
        this.evaluationLayerAgent = evaluationLayerAgent;
        this.growthLayerAgent = growthLayerAgent;
        this.resumeLoader = resumeLoader;
        this.sessionRepository = sessionRepository;
        this.learningProfileService = learningProfileService;
    }

    public InterviewSession startSession(String userId, String topic, String resumePath, Integer totalQuestions) {
        // 1) 装载简历上下文，作为首题与后续追问的背景资料。
        String resumeContent = "";
        if (resumePath != null && !resumePath.isEmpty()) {
            resumeContent = resumeLoader.loadResume(resumePath).stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n"));
        }

        // 2) 绑定用户画像快照并生成首题。
        int normalizedTotal = (totalQuestions == null || totalQuestions < 1) ? 5 : Math.min(totalQuestions, 20);
        InterviewSession session = new InterviewSession(topic, resumeContent, normalizedTotal);
        String normalizedUserId = learningProfileService.normalizeUserId(userId);
        session.setUserId(normalizedUserId);
        String profileSnapshot = learningProfileService.snapshotForPrompt(normalizedUserId, topic);
        session.setProfileSnapshot(profileSnapshot);
        String firstQuestion = evaluationAgent.generateFirstQuestion(resumeContent, topic, profileSnapshot);
        session.setCurrentQuestion(firstQuestion);
        return sessionRepository.save(session);
    }

    public AnswerResult submitAnswer(String sessionId, String userAnswer) {
        InterviewSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found"));

        String currentQ = session.getCurrentQuestion();
        // 决策层：根据掌握度与追问状态给出本轮策略。
        DecisionLayerAgent.DecisionPlan decisionPlan = decisionLayerAgent.plan(
                session.getTopic(),
                session.getDifficultyLevel().name(),
                session.getFollowUpState().name(),
                session.getTopicMastery(session.getTopic()),
                session.getProfileSnapshot(),
                session.getHistory().size()
        );
        // 知识层：构建本轮检索结果与证据包。
        RAGService.KnowledgePacket packet = knowledgeLayerAgent.gatherKnowledge(currentQ, userAnswer);
        // 评估层：结合策略与证据做结构化评分。
        EvaluationAgent.LayeredEvaluation layeredEvaluation = evaluationLayerAgent.evaluate(
                session.getTopic(),
                currentQ,
                userAnswer,
                session.getDifficultyLevel().name(),
                session.getFollowUpState().name(),
                session.getTopicMastery(session.getTopic()),
                session.getProfileSnapshot(),
                decisionPlan,
                packet
        );
        EvaluationAgent.EvaluationResult evaluation = layeredEvaluation.result();
        // 成长层：补充“本轮策略/聚焦/来源”反馈，提升可解释性。
        String growthFeedback = growthLayerAgent.composeRoundFeedback(evaluation.feedback(), decisionPlan, layeredEvaluation.trace());
        session.addHistory(new Question(
                currentQ,
                userAnswer,
                evaluation.score(),
                evaluation.accuracy(),
                evaluation.logic(),
                evaluation.depth(),
                evaluation.boundary(),
                evaluation.deductions(),
                String.join("\n", evaluation.citations()),
                String.join("\n", evaluation.conflicts()),
                growthFeedback
        ));
        session.updateAdaptiveState(session.getTopic(), evaluation.score());

        boolean finished = session.getHistory().size() >= session.getTotalQuestions();
        if (!finished) {
            session.setCurrentQuestion(evaluation.nextQuestion());
        } else {
            session.setCurrentQuestion("");
        }
        sessionRepository.save(session);

        return new AnswerResult(
                evaluation.score(),
                growthFeedback,
                finished ? "" : evaluation.nextQuestion(),
                session.getAverageScore(),
                finished,
                session.getHistory().size(),
                session.getTotalQuestions(),
                session.getDifficultyLevel().name(),
                session.getFollowUpState().name(),
                session.getTopicMastery(session.getTopic()),
                evaluation.accuracy(),
                evaluation.logic(),
                evaluation.depth(),
                evaluation.boundary(),
                evaluation.deductions(),
                evaluation.citations(),
                evaluation.conflicts()
        );
    }

    public InterviewSession getSession(String sessionId) {
        return sessionRepository.findById(sessionId).orElse(null);
    }

    public FinalReport generateFinalReport(String sessionId, String userId) {
        InterviewSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found"));
        if (session.getHistory().isEmpty()) {
            throw new IllegalStateException("还没有可总结的答题记录");
        }

        // 把历史答题沉淀进学习画像，再由成长层给出下一阶段训练重点。
        String profileUserId = session.getUserId();
        if (profileUserId == null || profileUserId.isBlank()) {
            profileUserId = learningProfileService.normalizeUserId(userId);
            session.setUserId(profileUserId);
        }
        String targetedSuggestion = learningProfileService.buildTargetedSuggestion(profileUserId);
        EvaluationAgent.FinalReportContent report = evaluationAgent.summarize(session.getTopic(), session.getHistory(), targetedSuggestion);
        learningProfileService.recordSession(profileUserId, session.getTopic(), session.getHistory(), report, session.getAverageScore());
        String summary = report.summary().isBlank() ? "本次面试已完成。建议重点复习评分较低题目的核心知识点。" : report.summary();
        String incomplete = report.incomplete().isBlank() ? "暂无明显不完整回答。" : report.incomplete();
        String weak = report.weak().isBlank() ? "暂无明显薄弱点。" : report.weak();
        String wrong = report.wrong().isBlank() ? "暂无明确错误结论。" : report.wrong();
        String obsidianUpdates = report.obsidianUpdates().isBlank() ? "建议补充：核心定义、实现原理、常见误区、边界条件。" : report.obsidianUpdates();
        String nextFocusSeed = report.nextFocus().isBlank() ? learningProfileService.buildTargetedSuggestion(profileUserId) : report.nextFocus();
        String nextFocus = growthLayerAgent.refineNextFocus(nextFocusSeed, targetedSuggestion, session.getAverageScore());

        return new FinalReport(summary, incomplete, weak, wrong, obsidianUpdates, nextFocus, session.getAverageScore(), session.getHistory().size());
    }

    public record AnswerResult(
            int score,
            String feedback,
            String nextQuestion,
            double averageScore,
            boolean finished,
            int answeredCount,
            int totalQuestions,
            String difficultyLevel,
            String followUpState,
            double topicMastery,
            int accuracy,
            int logic,
            int depth,
            int boundary,
            String deductions,
            List<String> citations,
            List<String> conflicts
    ) {
    }

    public record FinalReport(
            String summary,
            String incomplete,
            String weak,
            String wrong,
            String obsidianUpdates,
            String nextFocus,
            double averageScore,
            int answeredCount
    ) {
    }
}
