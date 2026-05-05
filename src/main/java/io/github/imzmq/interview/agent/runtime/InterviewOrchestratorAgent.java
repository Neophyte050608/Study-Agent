package io.github.imzmq.interview.agent.runtime;

import io.github.imzmq.interview.interview.domain.InterviewSession;
import io.github.imzmq.interview.interview.domain.InterviewStage;
import io.github.imzmq.interview.interview.domain.Question;
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
 *   <li>1. 决策层：根据会话状态（难度、追问阶段、掌握度、历史轮次）生成本轮出题策略。</li>
 *   <li>2. 知识层 (KnowledgeLayerAgent)：对"题目 + 回答"进行检索增强（RAG），产出支撑评估的专业证据包。</li>
 *   <li>3. 评估层：将策略、证据包及上下文输入模型，产出结构化评分与下一题。</li>
 *   <li>4. 成长层：将评估结果加工为可解释的成长反馈，并在报告阶段生成训练重点。</li>
 * </ul>
 *
 * 会话状态管理：每轮将题目、回答、评分、证据写入 history，并实时更新自适应状态机（难度、追问、掌握度）。
 */
@Component
public class InterviewOrchestratorAgent {

    private static final Logger logger = LoggerFactory.getLogger(InterviewOrchestratorAgent.class);

    private final EvaluationAgent evaluationAgent;
    private final KnowledgeLayerAgent knowledgeLayerAgent;
    private final ResumeLoader resumeLoader;
    private final SessionRepository sessionRepository;
    private final LearningProfileAgent learningProfileAgent;
    private final InputSanitizer inputSanitizer;
    /** Agent-to-Agent 消息总线，用于跨 Agent 异步发送滚动总结任务 */
    private final io.github.imzmq.interview.agent.a2a.A2ABus a2aBus;
    /** 自定义画像异步更新线程池 */
    private final java.util.concurrent.Executor profileUpdateExecutor;

    public InterviewOrchestratorAgent(EvaluationAgent evaluationAgent, KnowledgeLayerAgent knowledgeLayerAgent, ResumeLoader resumeLoader, SessionRepository sessionRepository, LearningProfileAgent learningProfileAgent, InputSanitizer inputSanitizer, io.github.imzmq.interview.agent.a2a.A2ABus a2aBus, @org.springframework.beans.factory.annotation.Qualifier("profileUpdateExecutor") java.util.concurrent.Executor profileUpdateExecutor) {
        this.evaluationAgent = evaluationAgent;
        this.knowledgeLayerAgent = knowledgeLayerAgent;
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
    public InterviewSession startSession(String userId, String topic, String resumePath, Integer totalQuestions, boolean skipIntro, List<String> excludedTopics) {
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
            session.setCurrentStage(InterviewStage.RESUME_DEEP_DIVE);
        } else {
            session.setCurrentStage(InterviewStage.INTRODUCTION);
        }

        // 3) 生成首题：输入包含简历与画像，使问题更贴近个人经历与当前薄弱点
        // 这里在后续可以根据 currentStage 进行定制化生成，目前暂且复用原有首题生成
        String firstQuestion = evaluationAgent.generateFirstQuestion(resumeContent, topic, profileSnapshot, skipIntro, excludedTopics);
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
        InterviewStage currentStage = session.getCurrentStage();
        InterviewStage expectedNextStage = currentStage;
        int nextHistorySize = session.getHistory().size() + 1;
        int totalQ = session.getTotalQuestions();

        if (currentStage == InterviewStage.INTRODUCTION && isReadyForResumeDive(nextHistorySize, totalQ)) {
            expectedNextStage = InterviewStage.RESUME_DEEP_DIVE;
        } else if (currentStage == InterviewStage.RESUME_DEEP_DIVE && isReadyForCoreKnowledge(nextHistorySize, totalQ)) {
            expectedNextStage = InterviewStage.CORE_KNOWLEDGE;
        } else if (currentStage == InterviewStage.CORE_KNOWLEDGE && isReadyForCoding(nextHistorySize, totalQ)) {
            expectedNextStage = InterviewStage.SCENARIO_OR_CODING;
        } else if (currentStage == InterviewStage.SCENARIO_OR_CODING && isReadyForClosing(nextHistorySize, totalQ)) {
            expectedNextStage = InterviewStage.CLOSING;
        }

        // 1.1 决策层：确定本轮出题/评估策略（inlined from DecisionLayerAgent）
        DecisionPlan decisionPlan = planDecision(
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

        // 3. 评估层：产出结构化打分（总分+各维度）与下一题（inlined from EvaluationLayerAgent）
        String strategy = decisionPlan == null ? "" : decisionPlan.strategyHint();
        EvaluationAgent.LayeredEvaluation layeredEvaluation = evaluationAgent.evaluateAnswerWithKnowledge(
                session.getTopic(),
                currentQ,
                wrappedAnswer,
                session.getDifficultyLevel().name(),
                session.getFollowUpState().name(),
                session.getTopicMastery(session.getTopic()),
                session.getProfileSnapshot(),
                strategy,
                packet
        );
        EvaluationAgent.EvaluationResult evaluation = layeredEvaluation.result();

        // 4. 成长层：加工生成更具指导性的成长反馈（inlined from GrowthLayerAgent.composeRoundFeedback）
        String growthFeedback = evaluation.feedback() == null ? "" : evaluation.feedback().trim();

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

        // 5.1 Direct stage transition (replaces COLA statemachine ceremonial overlay)
        boolean finished = session.getHistory().size() >= session.getTotalQuestions();
        InterviewStage nextStage;
        if (finished) {
            nextStage = InterviewStage.CLOSING;
        } else if (expectedNextStage != null && expectedNextStage != currentStage) {
            nextStage = expectedNextStage;
        } else {
            nextStage = currentStage;
        }
        session.setCurrentStage(nextStage);
        logger.info("====== [Interview] SOP 阶段流转: {} -> {} (会话: {}, 历史: {}/{}) ======",
                currentStage.name(), nextStage.name(), session.getId(),
                session.getHistory().size(), session.getTotalQuestions());

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

        // 成长层精炼最终建议（inlined from GrowthLayerAgent.refineNextFocus）
        String nextFocus = refineNextFocus(nextFocusSeed, targetedSuggestion, session.getAverageScore());

        FinalReport finalReport = new FinalReport(summary, incomplete, weak, wrong, obsidianUpdates, nextFocus, session.getAverageScore(), session.getHistory().size());

        return finalReport;
    }

    // ---------------------------------------------------------------
    // Private helpers — inlined from former LayerAgents
    // ---------------------------------------------------------------

    /**
     * Inlined from DecisionLayerAgent — 生成当前轮次的决策计划。
     */
    private DecisionPlan planDecision(String topic, String difficultyLevel, String followUpState, double topicMastery, String profileSnapshot, int answeredCount, InterviewStage currentStage, InterviewStage nextStage) {
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
     * Inlined from GrowthLayerAgent.refineNextFocus — 精炼下一阶段的学习/面试重点建议。
     */
    private String refineNextFocus(String nextFocus, String targetedSuggestion, double averageScore) {
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

    /**
     * 决策计划结果类（inlined from DecisionLayerAgent.DecisionPlan）。
     *
     * @param strategyHint 给大模型的策略提示词
     * @param focusHint 给大模型的聚焦重点提示词
     */
    private record DecisionPlan(
            String strategyHint,
            String focusHint
    ) {
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

    // --- Stage transition threshold helpers (inlined from InterviewStateMachineConfig) ---

    private static boolean isReadyForResumeDive(int historySize, int totalQuestions) {
        return historySize >= 1;
    }

    private static boolean isReadyForCoreKnowledge(int historySize, int totalQuestions) {
        int remaining = Math.max(0, totalQuestions - 2);
        int resumeCount = Math.max(1, (int) (remaining * 0.3));
        return historySize >= 1 + resumeCount;
    }

    private static boolean isReadyForCoding(int historySize, int totalQuestions) {
        int remaining = Math.max(0, totalQuestions - 2);
        int resumeCount = Math.max(1, (int) (remaining * 0.3));
        int coreCount = Math.max(1, (int) (remaining * 0.4));
        return historySize >= 1 + resumeCount + coreCount;
    }

    private static boolean isReadyForClosing(int historySize, int totalQuestions) {
        return historySize >= Math.max(1, totalQuestions - 1);
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
