package io.github.imzmq.interview.agent.runtime;

import com.alibaba.cola.statemachine.StateMachine;
import com.alibaba.cola.statemachine.StateMachineFactory;
import io.github.imzmq.interview.interview.domain.InterviewSession;
import io.github.imzmq.interview.interview.domain.Question;
import io.github.imzmq.interview.interview.domain.statemachine.InterviewContext;
import io.github.imzmq.interview.interview.domain.statemachine.InterviewEvent;
import io.github.imzmq.interview.interview.domain.statemachine.InterviewStateMachineConfig;
import io.github.imzmq.interview.rag.core.ResumeLoader;
import io.github.imzmq.interview.security.core.InputSanitizer;
import io.github.imzmq.interview.learning.application.LearningEvent;
import io.github.imzmq.interview.learning.application.LearningProfileAgent;
import io.github.imzmq.interview.learning.application.LearningSource;
import io.github.imzmq.interview.knowledge.application.RAGService;
import io.github.imzmq.interview.session.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 面试编排中枢（InterviewOrchestratorAgent）。
 * 
 * 核心职责：作为面试流程的"总导演"，协调多个专业智能体（Agent）完成一次完整的面试。
 * 
 * 职责边界（按一次答题回合）：
 * <ul>
 *   <li>1. 决策层 (DecisionLayerAgent)：根据会话状态（难度、追问阶段、掌握度、历史轮次）生成本轮出题策略。</li>
 *   <li>2. 知识层 (KnowledgeLayerAgent)：对"题目 + 回答"进行检索增强（RAG），产出支撑评估的专业证据包。</li>
 *   <li>3. 评估层 (EvaluationLayerAgent)：将策略、证据包及上下文输入模型，产出结构化评分与下一题。</li>
 *   <li>4. 成长层 (GrowthLayerAgent)：将评估结果加工为可解释的成长反馈，并在报告阶段生成训练重点。</li>
 * </ul>
 * 
 * 会话状态管理：每轮将题目、回答、评分、证据写入 history，并实时更新自适应状态机（难度、追问、掌握度）。
 */
@Component
public class InterviewOrchestratorAgent {

    private static final Logger logger = LoggerFactory.getLogger(InterviewOrchestratorAgent.class);

    private final EvaluationAgent evaluationAgent;
    private final KnowledgeLayerAgent knowledgeLayerAgent;
    private final DecisionLayerAgent decisionLayerAgent;
    private final EvaluationLayerAgent evaluationLayerAgent;
    private final GrowthLayerAgent growthLayerAgent;
    private final ResumeLoader resumeLoader;
    private final SessionRepository sessionRepository;
    private final LearningProfileAgent learningProfileAgent;
    private final InputSanitizer inputSanitizer;
    /** Agent-to-Agent 消息总线，用于跨 Agent 异步发送滚动总结任务 */
    private final io.github.imzmq.interview.agent.a2a.A2ABus a2aBus;
    /** 自定义画像异步更新线程池 */
    private final java.util.concurrent.Executor profileUpdateExecutor;

    public InterviewOrchestratorAgent(EvaluationAgent evaluationAgent, KnowledgeLayerAgent knowledgeLayerAgent, DecisionLayerAgent decisionLayerAgent, EvaluationLayerAgent evaluationLayerAgent, GrowthLayerAgent growthLayerAgent, ResumeLoader resumeLoader, SessionRepository sessionRepository, LearningProfileAgent learningProfileAgent, InputSanitizer inputSanitizer, io.github.imzmq.interview.agent.a2a.A2ABus a2aBus, @org.springframework.beans.factory.annotation.Qualifier("profileUpdateExecutor") java.util.concurrent.Executor profileUpdateExecutor) {
        this.evaluationAgent = evaluationAgent;
        this.knowledgeLayerAgent = knowledgeLayerAgent;
        this.decisionLayerAgent = decisionLayerAgent;
        this.evaluationLayerAgent = evaluationLayerAgent;
        this.growthLayerAgent = growthLayerAgent;
        this.resumeLoader = resumeLoader;
        this.sessionRepository = sessionRepository;
        this.learningProfileAgent = learningProfileAgent;
        this.inputSanitizer = inputSanitizer;
        this.a2aBus = a2aBus;
        this.profileUpdateExecutor = profileUpdateExecutor;
    }

    /**
     * 创建一次新的面试会话并生成首题。
     *
     * @param userId 用户ID
     * @param topic 面试主题
     * @param resumePath 简历文件路径（可选）
     * @param totalQuestions 期望面试总题数（可选，默认5，范围1-20）
     * @return 包含首题、简历内容与画像快照的会话对象
     */
    public InterviewSession startSession(String userId, String topic, String resumePath, Integer totalQuestions, boolean skipIntro) {
        // 1) 装载简历上下文：作为首题生成与后续追问的背景资料
        String resumeContent = "";
        if (resumePath != null && !resumePath.isEmpty()) {
            resumeContent = resumeLoader.loadResume(resumePath).stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n"));
        }

        // 2) 绑定用户画像快照：把"历史学习记录/训练偏好"压缩为可直接拼进提示词的文本
        int normalizedTotal = (totalQuestions == null || totalQuestions < 1) ? 5 : Math.min(totalQuestions, 20);
        InterviewSession session = new InterviewSession(topic, resumeContent, normalizedTotal);
        String normalizedUserId = learningProfileAgent.normalizeUserId(userId);
        session.setUserId(normalizedUserId);
        String profileSnapshot = learningProfileAgent.snapshotForPrompt(normalizedUserId, topic);
        session.setProfileSnapshot(profileSnapshot);
        
        // SOP 状态机增强：初始化状态并生成对应环节的首题
        if (skipIntro) {
            session.setCurrentStage(io.github.imzmq.interview.interview.domain.InterviewStage.RESUME_DEEP_DIVE);
        } else {
            session.setCurrentStage(io.github.imzmq.interview.interview.domain.InterviewStage.INTRODUCTION);
        }

        // 3) 生成首题：输入包含简历与画像，使问题更贴近个人经历与当前薄弱点
        // 这里在后续可以根据 currentStage 进行定制化生成，目前暂且复用原有首题生成
        String firstQuestion = evaluationAgent.generateFirstQuestion(resumeContent, topic, profileSnapshot, skipIntro);
        session.setCurrentQuestion(firstQuestion);
        
        // 持久化会话（支持断电恢复）
        return sessionRepository.save(session);
    }

    /**
     * 提交用户回答并推进面试进度。
     *
     * 核心流程：决策(plan) -> 知识检索(gather) -> 评估(evaluate) -> 成长反馈(compose) -> 状态更新。
     * 
     * @param sessionId 会话ID
     * @param userAnswer 用户输入的回答文本
     * @return 包含评分、反馈、下一题及实时画像统计的结果对象
     */
    public AnswerResult submitAnswer(String sessionId, String userAnswer) {
        InterviewSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found"));

        // Phase 3.1: 已结束面试拒绝再答
        if (session.getHistory().size() >= session.getTotalQuestions()) {
            throw new IllegalStateException("面试已结束，请调用 /api/report 获取总结报告");
        }

        // Phase 1.1: 输入净化 + 边界包装
        String sanitizedAnswer = inputSanitizer.sanitize(userAnswer);

        // Phase 2.3: 垃圾回答快速拦截（不走 LLM）
        if (isGarbageAnswer(sanitizedAnswer)) {
            String currentQ = session.getCurrentQuestion();
            session.addHistory(new Question(
                    currentQ, sanitizedAnswer, 5, 0, 0, 0, 0,
                    "回答过于简短或无实质内容", "", "", "回答过于简短或无实质内容，请认真思考后再作答。"));
            session.updateAdaptiveState(session.getTopic(), 5);
            session.setCurrentQuestion(currentQ); // 保留原题，让用户重答
            sessionRepository.save(session);
            return new AnswerResult(
                    5, "回答过于简短或无实质内容，请重新作答。", currentQ,
                    session.getAverageScore(), false,
                    session.getHistory().size(), session.getTotalQuestions(),
                    session.getDifficultyLevel().name(), session.getFollowUpState().name(),
                    session.getTopicMastery(session.getTopic()),
                    0, 0, 0, 0, "", List.of(), List.of());
        }

        // 传给 LLM 评估的使用边界包装版本（防注入）
        String wrappedAnswer = inputSanitizer.wrapWithBoundary(sanitizedAnswer);

        String currentQ = session.getCurrentQuestion();
        
        // 1. 预判下一题的阶段 (因为当前轮结束也就是 history.size() + 1)
        io.github.imzmq.interview.interview.domain.InterviewStage currentStage = session.getCurrentStage();
        io.github.imzmq.interview.interview.domain.InterviewStage expectedNextStage = currentStage;
        int nextHistorySize = session.getHistory().size() + 1;
        int totalQ = session.getTotalQuestions();
        
        if (currentStage == io.github.imzmq.interview.interview.domain.InterviewStage.INTRODUCTION && InterviewStateMachineConfig.isReadyForResumeDive(nextHistorySize, totalQ)) {
            expectedNextStage = io.github.imzmq.interview.interview.domain.InterviewStage.RESUME_DEEP_DIVE;
        } else if (currentStage == io.github.imzmq.interview.interview.domain.InterviewStage.RESUME_DEEP_DIVE && InterviewStateMachineConfig.isReadyForCoreKnowledge(nextHistorySize, totalQ)) {
            expectedNextStage = io.github.imzmq.interview.interview.domain.InterviewStage.CORE_KNOWLEDGE;
        } else if (currentStage == io.github.imzmq.interview.interview.domain.InterviewStage.CORE_KNOWLEDGE && InterviewStateMachineConfig.isReadyForCoding(nextHistorySize, totalQ)) {
            expectedNextStage = io.github.imzmq.interview.interview.domain.InterviewStage.SCENARIO_OR_CODING;
        } else if (currentStage == io.github.imzmq.interview.interview.domain.InterviewStage.SCENARIO_OR_CODING && InterviewStateMachineConfig.isReadyForClosing(nextHistorySize, totalQ)) {
            expectedNextStage = io.github.imzmq.interview.interview.domain.InterviewStage.CLOSING;
        }

        // 1.1 决策层：确定本轮出题/评估策略
        DecisionLayerAgent.DecisionPlan decisionPlan = decisionLayerAgent.plan(
                session.getTopic(),
                session.getDifficultyLevel().name(),
                session.getFollowUpState().name(),
                session.getTopicMastery(session.getTopic()),
                session.getProfileSnapshot(),
                session.getHistory().size(),
                currentStage,
                expectedNextStage
        );

        // 2. 知识层：针对问答进行检索增强（用净化后的原文检索，非包装版本）
        RAGService.KnowledgePacket packet = knowledgeLayerAgent.gatherKnowledge(currentQ, sanitizedAnswer);

        // 3. 评估层：产出结构化打分（总分+各维度）与下一题（传入边界包装版本防注入）
        EvaluationAgent.LayeredEvaluation layeredEvaluation = evaluationLayerAgent.evaluate(
                session.getTopic(),
                currentQ,
                wrappedAnswer,
                session.getDifficultyLevel().name(),
                session.getFollowUpState().name(),
                session.getTopicMastery(session.getTopic()),
                session.getProfileSnapshot(),
                decisionPlan,
                packet
        );
        EvaluationAgent.EvaluationResult evaluation = layeredEvaluation.result();

        // 4. 成长层：加工生成更具指导性的成长反馈
        String growthFeedback = growthLayerAgent.composeRoundFeedback(evaluation.feedback(), decisionPlan, layeredEvaluation.trace());
        
        // 5. 记录历史并更新状态（存历史用净化后原文，不带边界标记）
        session.addHistory(new Question(
                currentQ,
                sanitizedAnswer,
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
        
        // 5.1 引入 COLA 状态机进行严格的 SOP 流程控场
        StateMachine<io.github.imzmq.interview.interview.domain.InterviewStage, InterviewEvent, InterviewContext> stateMachine = 
                StateMachineFactory.get(InterviewStateMachineConfig.MACHINE_ID);
        
        InterviewContext context = new InterviewContext(session, sanitizedAnswer, evaluation.score());
        io.github.imzmq.interview.interview.domain.InterviewStage previousStage = session.getCurrentStage();
        
        // 尝试触发多个阶段流转事件（COLA 状态机会根据 Condition 自动决定是否流转）
        // 因为在一个回合中，可能刚好满足进入下一个阶段的条件
        io.github.imzmq.interview.interview.domain.InterviewStage nextStage = session.getCurrentStage();
        if (previousStage == io.github.imzmq.interview.interview.domain.InterviewStage.INTRODUCTION) {
            nextStage = stateMachine.fireEvent(previousStage, InterviewEvent.FINISH_INTRO, context);
            if (nextStage != null) session.setCurrentStage(nextStage);
        } else if (previousStage == io.github.imzmq.interview.interview.domain.InterviewStage.RESUME_DEEP_DIVE) {
            nextStage = stateMachine.fireEvent(previousStage, InterviewEvent.COMPLETE_RESUME_DIVE, context);
            if (nextStage != null) session.setCurrentStage(nextStage);
        } else if (previousStage == io.github.imzmq.interview.interview.domain.InterviewStage.CORE_KNOWLEDGE) {
            nextStage = stateMachine.fireEvent(previousStage, InterviewEvent.COMPLETE_CORE_KNOWLEDGE, context);
            if (nextStage != null) session.setCurrentStage(nextStage);
        } else if (previousStage == io.github.imzmq.interview.interview.domain.InterviewStage.SCENARIO_OR_CODING) {
            nextStage = stateMachine.fireEvent(previousStage, InterviewEvent.COMPLETE_CODING, context);
            if (nextStage != null) session.setCurrentStage(nextStage);
        }

        // 当面试已结束但状态机还没到达 CLOSING 时，强制跳转
        boolean finished = session.getHistory().size() >= session.getTotalQuestions();
        if (finished && nextStage != io.github.imzmq.interview.interview.domain.InterviewStage.CLOSING) {
            nextStage = io.github.imzmq.interview.interview.domain.InterviewStage.CLOSING;
            session.setCurrentStage(nextStage);
        }

        // [长对话滚动式总结优化]：当对话轮数达到阈值（如5轮）时，触发 RocketMQ 异步总结任务
        int dialogueCount = session.getHistory().size();
        if (dialogueCount > 0 && dialogueCount % 5 == 0) {
            logger.debug("====== [InterviewOrchestratorAgent] 触发异步滚动总结 (当前轮数: {}) ======", dialogueCount);
            // 获取最近5轮的对话数据
            List<Question> recentQuestions = session.getHistory().subList(dialogueCount - 5, dialogueCount);
            List<Map<String, Object>> recentHistory = recentQuestions.stream().map(q -> Map.of(
                    "question", (Object) q.getQuestionText(),
                    "answer", (Object) q.getUserAnswer(),
                    "feedback", (Object) q.getFeedback()
            )).toList();

            // 发送异步 MQ 消息，交给 RollingSummaryAgent 处理
            io.github.imzmq.interview.agent.a2a.A2AMessage summaryMsg = new io.github.imzmq.interview.agent.a2a.A2AMessage(
                    "1.0",
                    java.util.UUID.randomUUID().toString(),
                    java.util.UUID.randomUUID().toString(),
                    "InterviewOrchestratorAgent",
                    "RollingSummaryAgent",
                    "",
                    io.github.imzmq.interview.agent.a2a.A2AIntent.ROLLING_SUMMARY,
                    Map.of(
                            "sessionId", session.getId(),
                            "targetCount", dialogueCount,
                            "recentHistory", recentHistory
                    ),
                    Map.of(),
                    io.github.imzmq.interview.agent.a2a.A2AStatus.PENDING,
                    null,
                    new io.github.imzmq.interview.agent.a2a.A2AMetadata("rolling-summary", "InterviewOrchestratorAgent", Map.of()),
                    new io.github.imzmq.interview.agent.a2a.A2ATrace(java.util.UUID.randomUUID().toString(), null),
                    java.time.Instant.now()
            );
            a2aBus.publish(summaryMsg);
        }

        // 6. 自适应状态机更新：回写掌握度、难度和追问阶段
        session.updateAdaptiveState(session.getTopic(), evaluation.score());

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

    /**
     * 生成最终面试报告，并将本次练习结果沉淀进学习画像。
     *
     * 流程：总结报告 -> 写入画像事件 -> 生成成长建议 -> 返回报告对象。
     * 
     * @param sessionId 会话ID
     * @param userId 用户ID
     * @return 包含总结、薄弱点、错误、改进方向及平均分的最终报告
     */
    public FinalReport generateFinalReport(String sessionId, String userId) {
        InterviewSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found"));
        if (session.getHistory().isEmpty()) {
            throw new IllegalStateException("还没有可总结的答题记录");
        }

        // 处理用户ID归一化
        String profileUserId = session.getUserId();
        if (profileUserId == null || profileUserId.isBlank()) {
            profileUserId = learningProfileAgent.normalizeUserId(userId);
            session.setUserId(profileUserId);
        }
        
        // 生成总结与画像更新
        String targetedSuggestion = learningProfileAgent.recommend(profileUserId, "interview");
        EvaluationAgent.FinalReportContent report = evaluationAgent.summarize(session.getTopic(), session.getHistory(), targetedSuggestion, session.getRollingSummary());
        
        // 异步沉淀为画像事件（LearningEvent），避免阻塞最终报告的返回，使用自定义线程池 profileUpdateExecutor
        final String finalProfileUserId = profileUserId;
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                logger.debug("====== [InterviewOrchestratorAgent] 异步更新学习画像开始 ======");
                learningProfileAgent.upsertEvent(new LearningEvent(
                        "interview-" + session.getId() + "-" + UUID.randomUUID(),
                        finalProfileUserId,
                        LearningSource.INTERVIEW,
                        session.getTopic(),
                        Math.max(0, Math.min(100, (int) Math.round(session.getAverageScore()))),
                        mergePoints(report.weak(), report.incomplete(), report.wrong()),
                        deriveFamiliarPoints(session.getHistory()),
                        report.summary(),
                        Instant.now()
                ));
                logger.debug("====== [InterviewOrchestratorAgent] 异步更新学习画像完成 ======");
            } catch (Exception e) {
                logger.error("====== [InterviewOrchestratorAgent] 异步更新学习画像失败 ======", e);
            }
        }, profileUpdateExecutor);
        
        // 填充默认值与精炼成长建议
        String summary = report.summary().isBlank() ? "本次面试已完成。建议重点复习评分较低题目的核心知识点。" : report.summary();
        String incomplete = report.incomplete().isBlank() ? "暂无明显不完整回答。" : report.incomplete();
        String weak = report.weak().isBlank() ? "暂无明显薄弱点。" : report.weak();
        String wrong = report.wrong().isBlank() ? "暂无明确错误结论。" : report.wrong();
        String obsidianUpdates = report.obsidianUpdates().isBlank() ? "建议补充：核心定义、实现原理、常见误区、边界条件。" : report.obsidianUpdates();
        String nextFocusSeed = report.nextFocus().isBlank() ? targetedSuggestion : report.nextFocus();
        
        // 成长层精炼最终建议
        String nextFocus = growthLayerAgent.refineNextFocus(nextFocusSeed, targetedSuggestion, session.getAverageScore());

        FinalReport finalReport = new FinalReport(summary, incomplete, weak, wrong, obsidianUpdates, nextFocus, session.getAverageScore(), session.getHistory().size());
        
        // 发布面试完成事件到 MQ (A2ABus) 以便后续解耦处理（如更新画像、推送通知等）
        io.github.imzmq.interview.agent.task.TaskResponse a2aResponse = io.github.imzmq.interview.agent.task.TaskResponse.ok(finalReport);
        // 这里不直接依赖 A2ABus，因为我们在 TaskRouterAgent 层面统一处理了 TaskResponse 的回传，
        // 但由于 generateFinalReport 是被调用的，路由代理会在结束时发出 A2AStatus.DONE 的消息。
        // 如果想要发送业务意义更强的事件，应该通过 TaskRouterAgent 的上层或由 Orchestrator 直接注入 A2ABus。
        
        return finalReport;
    }

    /**
     * 把多段文本（weak/incomplete/wrong）合并为"要点列表"。
     *
     * <p>规则：按换行/分号切分、去掉开头的序号/项目符号、每段最多取 8 条、整体去重后最多保留 8 条。</p>
     */
    private List<String> mergePoints(String... texts) {
        List<String> points = new ArrayList<>();
        if (texts == null) {
            return points;
        }
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            Arrays.stream(text.split("\\R|；|;"))
                    .map(String::trim)
                    .map(item -> item.replaceFirst("^[-•\\d.\\s]+", ""))
                    .filter(item -> !item.isBlank())
                    .limit(8)
                    .forEach(points::add);
        }
        return points.stream().distinct().limit(8).toList();
    }

    /**
     * 判断回答是否为垃圾内容(过短、纯标点、纯数字等),避免浪费 LLM 调用。
     */
    private boolean isGarbageAnswer(String answer) {
        if (answer == null) return true;
        String trimmed = answer.trim();
        if (trimmed.length() < 2) return true;
        // 去除所有空白和标点（含中文标点）后检查是否有实质内容
        String contentOnly = trimmed.replaceAll("[\\s\\p{P}\\p{S}]+", "");
        if (contentOnly.length() < 2) return true;
        // 纯数字
        if (contentOnly.matches("\\d+")) return true;
        return false;
    }

    /**
     * 从历史中提取"相对熟练点"，用于画像里的正向样本。
     *
     * <p>当前定义：分数 >= 80 的题干（最多 5 条）。这里是启发式规则，目的是为后续训练推荐提供种子。</p>
     */
    private List<String> deriveFamiliarPoints(List<Question> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return history.stream()
                .filter(item -> item.getScore() >= 80)
                .map(Question::getQuestionText)
                .map(item -> item == null ? "" : item.trim())
                .filter(item -> !item.isBlank())
                .limit(5)
                .toList();
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








