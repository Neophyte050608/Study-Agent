package io.github.imzmq.interview.knowledge.application;

import io.github.imzmq.interview.media.application.contract.result.ImageResult;

import io.github.imzmq.interview.knowledge.application.contract.result.KnowledgeInterviewReportEntry;
import io.github.imzmq.interview.observability.core.RAGTraceContext;
import io.github.imzmq.interview.modelrouting.core.ModelRouteType;
import io.github.imzmq.interview.modelrouting.core.RoutingChatService;
import io.github.imzmq.interview.modelrouting.core.TimeoutHint;
import io.github.imzmq.interview.agent.application.contract.AgentSkillInstructions;
import io.github.imzmq.interview.chat.application.contract.PromptRenderer;
import io.github.imzmq.interview.chat.application.contract.result.RenderedPrompt;
import io.github.imzmq.interview.observability.application.contract.trace.RagTraceOperations;
import io.github.imzmq.interview.skill.core.SkillExecutionBudget;
import io.github.imzmq.interview.skill.core.SkillExecutionContext;
import io.github.imzmq.interview.skill.core.SkillExecutionResult;
import io.github.imzmq.interview.skill.runtime.SkillOrchestrator;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.imzmq.interview.modelrouting.application.contract.structuredoutput.StructuredOutputResult;
import io.github.imzmq.interview.modelrouting.application.contract.structuredoutput.StructuredOutputParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * RAG 兼容门面：委托 KnowledgePacketBuilder 构建知识包，保留既有面试/学习生成入口。
 * 召回、融合与上下文装配由 knowledge.application.retrieval 中的专职实现承担。
 *
 * <p>关键约定：</p>
 * <ul>
 *   <li>证据索引：buildRetrievalEvidence/buildWebEvidence 会生成带编号的证据目录，评估输出的 citations/conflicts 只能引用这些编号。</li>
 *   <li>安全日志：日志输出会尽量使用长度与脱敏后的内容，避免把 API Key、Bearer token 等敏感信息写入日志。</li>
 *   <li>降级路径：模型/检索不可用时返回可读的 fallback JSON，保证前端流程可继续。</li>
 * </ul>
 */
@Service
public class RAGService {

    private static final Logger logger = LoggerFactory.getLogger(RAGService.class);
    // 用于日志脱敏：尽量在调试时保留“字段存在”信息，但不暴露实际密钥/令牌。
    private static final Pattern RAW_API_KEY_PATTERN = Pattern.compile("(?i)(\"?api[-_ ]?key\"?\\s*[:=]\\s*\"?)([^\",\\s]+)");
    private static final Pattern AUTHORIZATION_PATTERN = Pattern.compile("(?i)(authorization\\s*[:=]\\s*bearer\\s+)([A-Za-z0-9._-]{8,})");
    private static final Pattern LONG_TOKEN_PATTERN = Pattern.compile("\\b[A-Za-z0-9._-]{32,}\\b");
    private final RoutingChatService routingChatService;
    private final RagTraceOperations ragTraceOperations;
    private final AgentSkillInstructions agentSkillService;
    private final PromptRenderer promptRenderer;
    private final StructuredOutputParser structuredOutputParser;
    private final SkillOrchestrator skillOrchestrator;
    private final KnowledgePacketBuilder knowledgePacketBuilder;

    public RAGService(RoutingChatService routingChatService, RagTraceOperations ragTraceOperations,
                      AgentSkillInstructions agentSkillService, PromptRenderer promptRenderer,
                      SkillOrchestrator skillOrchestrator,
                      KnowledgePacketBuilder knowledgePacketBuilder, StructuredOutputParser structuredOutputParser) {
        this.agentSkillService = agentSkillService;
        this.promptRenderer = promptRenderer;
        this.routingChatService = routingChatService;
        this.ragTraceOperations = ragTraceOperations;
        this.skillOrchestrator = skillOrchestrator;
        this.knowledgePacketBuilder = knowledgePacketBuilder;
        this.structuredOutputParser = structuredOutputParser;
    }

    public record EvaluationResult(String json, int inputTokens, int outputTokens) {}

    /**
     * 构建知识包（默认允许 Web fallback）。
     *
     * <p>该方法通常用于线上链路：如果本地混合检索为空/质量不足，会根据开关策略触发 WebSearch 兜底。</p>
     *
     * @param question  用户问题
     * @param userAnswer 用户回答（可为空；用于改写检索 query）
     * @return 知识包（包含改写 query、召回文档列表、拼接后的上下文与证据目录）
     */
    public KnowledgePacket buildKnowledgePacket(String question, String userAnswer) {
        return buildKnowledgePacket(question, userAnswer, true);
    }

    /**
     * 构建检索知识包，并允许调用方决定是否启用 Web fallback。
     */
    public KnowledgePacket buildKnowledgePacket(String question, String userAnswer, boolean allowWebFallback) {
        return executeWithinTraceRoot("KNOWLEDGE_PACKET", "Knowledge Packet Build", "Q: " + question,
                () -> knowledgePacketBuilder.build(question, userAnswer, allowWebFallback));
    }

    private <T> T executeWithinTraceRoot(String nodeType, String nodeName, String inputSummary, Supplier<T> action) {
        String currentNodeId = RAGTraceContext.getCurrentNodeId();
        if (currentNodeId != null && !currentNodeId.isBlank()) {
            return action.get();
        }
        String traceId = RAGTraceContext.getTraceId();
        String rootNodeId = UUID.randomUUID().toString();
        ragTraceOperations.startNode(traceId, rootNodeId, null, nodeType, nodeName);
        try {
            T result = action.get();
            ragTraceOperations.endNode(traceId, rootNodeId, inputSummary, nodeName + " completed", null);
            return result;
        } catch (RuntimeException e) {
            ragTraceOperations.endNode(traceId, rootNodeId, inputSummary, null, summarizeError(e));
            throw e;
        }
    }

    private <T> T callWithRetry(Supplier<T> action, int maxAttempts, String stage) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                last = e;
                logger.warn("{}第{}次调用失败: {}", stage, attempt, summarizeError(e));
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(400L * attempt);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("线程中断", interruptedException);
                    }
                }
            }
        }
        throw last == null ? new IllegalStateException(stage + "失败") : last;
    }

    private String compactMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return message;
    }

    public String summarizeError(Throwable throwable) {
        String compact = sanitizeUpstreamText(compactMessage(throwable));
        String upstream = extractUpstreamDetail(throwable);
        if (upstream.isBlank()) {
            return compact;
        }
        return compact + " | upstream=" + upstream;
    }

    private String extractUpstreamDetail(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof RestClientResponseException responseException) {
                String body = responseException.getResponseBodyAsString();
                String detail = "status=" + responseException.getStatusCode().value() + ", body=" + sanitizeUpstreamText(body);
                return truncate(detail, 360);
            }
            current = current.getCause();
        }
        if (throwable == null || throwable.getMessage() == null) {
            return "";
        }
        return truncate(sanitizeUpstreamText(throwable.getMessage()), 300);
    }

    private String sanitizeUpstreamText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String sanitized = text.replaceAll("\\s+", " ").trim();
        sanitized = RAW_API_KEY_PATTERN.matcher(sanitized).replaceAll("$1***");
        sanitized = AUTHORIZATION_PATTERN.matcher(sanitized).replaceAll("$1***");
        sanitized = LONG_TOKEN_PATTERN.matcher(sanitized).replaceAll("***");
        return sanitized;
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxLength) + "...";
    }

    private int safeLength(String text) {
        return text == null ? 0 : text.length();
    }

    private String safeLogText(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return truncate(sanitizeUpstreamText(text), maxLength);
    }
    
    private String buildTopicWithExclusion(String topic, List<String> excludedTopics) {
        if (excludedTopics == null || excludedTopics.isEmpty()) {
            return topic;
        }
        return topic + "（注意：不得涉及以下知识点：" + String.join("、", excludedTopics) + "）";
    }

    public String generateFirstQuestion(String resumeContent, String topic, String profileSnapshot, boolean skipIntro, List<String> excludedTopics) {
         String skillBlock = safeSkillText(agentSkillService.resolveSkillBlock("question-strategy", "interview-learning-profile"));
         try {
            logger.debug("[generateFirstQuestion] Params: topic={}, skipIntro={}", topic, skipIntro);
            String strategySummary = resolveQuestionStrategySummary(
                    "first-question",
                    topic,
                    "",
                    "",
                    "",
                    profileSnapshot,
                    resumeContent,
                    skipIntro
            );
            Map<String, Object> vars = new HashMap<>();
            vars.put("skillBlock", mergeSkillGuidance(skillBlock, strategySummary));
            vars.put("resume", truncate(resumeContent, 1500));
            vars.put("profileSnapshot", truncate(profileSnapshot, 500));
            vars.put("topic", buildTopicWithExclusion(topic == null ? "" : topic, excludedTopics));
            vars.put("skipIntro", skipIntro);
            RenderedPrompt pair = promptRenderer.renderSplit("interviewer", "first-question", vars);
            String rawQuestion = routingChatService.callWithFirstPacketProbeSupplier(
                    () -> buildFallbackFirstQuestion(topic),
                    pair.systemPrompt(),
                    pair.userPrompt(),
                    ModelRouteType.GENERAL,
                    TimeoutHint.NORMAL,
                    "首题生成"
            );
            logger.debug("[generateFirstQuestion] Response length={}", rawQuestion == null ? 0 : rawQuestion.length());
             return normalizeFirstQuestion(rawQuestion, topic);
         } catch (RuntimeException e) {
             logger.warn("首题生成失败，返回降级问题。原因: {}", summarizeError(e));
             return buildFallbackFirstQuestion(topic);
         }
    }

    public String generateFinalReport(String topic, List<KnowledgeInterviewReportEntry> history, String targetedSuggestion, String rollingSummary) {
        // 使用新抽离的 interview-report-generator 和 interview-growth-coach 技能
        String skillBlock = safeSkillText(agentSkillService.resolveSkillBlock("interview-report-generator", "interview-growth-coach"));
        String reportStrategySummary = resolveFinalReportSummary(topic, history, targetedSuggestion, rollingSummary);
        List<KnowledgeInterviewReportEntry> safeHistory = history == null ? List.of() : history;
        
        // 结合 Rolling Summary 机制，如果存在滚动总结，则将其作为极高权重的历史基线传入
        String baseContext = rollingSummary == null || rollingSummary.isBlank() ? "" : "【前期面试表现滚动总结】\n" + rollingSummary + "\n\n";

        // Contextual Compression: 针对长达几十轮的面试历史，采用摘要式压缩
        // 因为已经有了 rollingSummary 覆盖了前面的对话，这里我们只需要保留最近 5 轮的详细记录即可，防止 Prompt 过长
        int historySize = safeHistory.size();
        int recentCount = Math.min(historySize, 5);
        List<KnowledgeInterviewReportEntry> recentHistoryList = recentCount <= 0
                ? List.of()
                : safeHistory.subList(historySize - recentCount, historySize);

        String qaHistory = recentHistoryList.isEmpty()
                ? "暂无有效面试记录。"
                : recentHistoryList.stream()
                .map(item -> {
                    // 对于最近 5 轮，我们仍然保留 150 个字符的截断作为双保险，避免个别回答异常冗长
                    String shortAnswer = item.answer() != null && item.answer().length() > 150
                            ? item.answer().substring(0, 150) + "..."
                            : item.answer();
                    return "Q: " + item.question() + "\n" +
                           "A(简): " + shortAnswer + "\n" +
                           "Score: " + item.score() + " | FB: " + item.feedback();
                })
                .collect(Collectors.joining("\n\n---\n\n"));

        Map<String, Object> params = new HashMap<>();
        params.put("skillBlock", mergeSkillGuidance(skillBlock, reportStrategySummary));
        params.put("topic", topic);
        params.put("targetedSuggestion", targetedSuggestion);
        params.put("baseContext", baseContext);
        params.put("history", qaHistory);
        RenderedPrompt pair = promptRenderer.renderSplit("interviewer", "final-report", params);

        try {
            logger.debug("[generateFinalReport] Prompt length={}", pair.userPrompt().length());
            String response = callWithRetry(() -> routingChatService.call(
                pair.systemPrompt(), pair.userPrompt(), ModelRouteType.THINKING, "最终复盘"
            ), 1, "最终复盘");
            logger.debug("====== [RAGService - generateFinalReport] Response ======");
            logger.debug("{}", response);
            return response;
        } catch (RuntimeException e) {
            logger.warn("最终复盘生成失败，返回降级报告。原因: {}", summarizeError(e));
            return buildFallbackReport(safeHistory, targetedSuggestion);
        }
    }

    public String generateLearningPlan(String topic, String weakPoint, String recentPerformance) {
        String normalizedTopic = topic == null || topic.isBlank() ? "后端基础" : topic.trim();
        // 使用新抽离的 personalized-learning-planner 技能
        String skillBlock = safeSkillText(agentSkillService.resolveSkillBlock("personalized-learning-planner", "interview-learning-profile", "interview-growth-coach"));
        String planningSummary = resolveLearningPlanSummary(normalizedTopic, weakPoint, recentPerformance);
        
        Map<String, Object> params = new HashMap<>();
        params.put("skillBlock", mergeSkillGuidance(skillBlock, planningSummary));
        params.put("topic", normalizedTopic);
        params.put("weakPoint", truncate(weakPoint, 200));
        params.put("recentPerformance", truncate(recentPerformance, 300));
        RenderedPrompt pair = promptRenderer.renderSplit("interviewer", "learning-plan", params);

        try {
            String raw = callWithRetry(() -> routingChatService.callWithFirstPacketProbeSupplier(
                () -> "",
                pair.systemPrompt(), pair.userPrompt(), ModelRouteType.GENERAL, TimeoutHint.NORMAL, "学习计划生成"
            ), 1, "学习计划生成");
            if (raw == null || raw.isBlank()) {
                return fallbackLearningPlan(normalizedTopic, weakPoint);
            }
            return raw.trim();
        } catch (RuntimeException e) {
            logger.warn("学习计划生成失败，返回降级计划。原因: {}", summarizeError(e));
            return fallbackLearningPlan(normalizedTopic, weakPoint);
        }
    }

    private String buildFallbackReport(List<KnowledgeInterviewReportEntry> history, String targetedSuggestion) {
        String incomplete = history.stream()
                .filter(q -> q.score() >= 60 && q.score() < 80)
                .map(q -> "问题《" + q.question() + "》：要点覆盖不完整。")
                .collect(Collectors.joining("\n"));
        String weak = history.stream()
                .filter(q -> q.score() >= 40 && q.score() < 60)
                .map(q -> "问题《" + q.question() + "》：表达或结构较弱。")
                .collect(Collectors.joining("\n"));
        String wrong = history.stream()
                .filter(q -> q.score() < 40)
                .map(q -> "问题《" + q.question() + "》：存在明显事实或原理错误。")
                .collect(Collectors.joining("\n"));
        String summary = "已完成本轮面试，请优先复习低分题的核心原理，并通过结构化回答提升完整度。";
        return "<summary>" + summary + "</summary>\n" +
                "<incomplete>" + (incomplete.isBlank() ? "暂无明显不完整回答。" : incomplete) + "</incomplete>\n" +
                "<weak>" + (weak.isBlank() ? "暂无明显薄弱表达。" : weak) + "</weak>\n" +
                "<wrong>" + (wrong.isBlank() ? "暂无明确错误结论。" : wrong) + "</wrong>\n" +
                "<obsidian_updates>建议补充：每道低分题补充定义、原理、场景和边界条件。</obsidian_updates>\n" +
                "<next_focus>" + (targetedSuggestion == null || targetedSuggestion.isBlank() ? "下一轮聚焦低分题相关主题。" : targetedSuggestion) + "</next_focus>";
    }

    private String buildFallbackFirstQuestion(String topic) {
        String safeTopic = topic == null || topic.isBlank() ? "后端开发" : topic.trim();
        return "你好！欢迎参加今天的「" + safeTopic + "」模拟面试。在正式开始技术交流之前，能请你先花 1-2 分钟做一个简单的自我介绍吗？";
    }

    private String fallbackLearningPlan(String topic, String weakPoint) {
        String weak = weakPoint == null || weakPoint.isBlank() ? "复杂度分析与边界处理" : weakPoint;
        return "Day1: 梳理 " + topic + " 核心概念，完成 2 道基础题，记录错误清单。\n" +
                "Day2: 复习 " + weak + "，完成 1 道中等题，复盘复杂度。\n" +
                "Day3: 总结高频模板，完成 2 道同主题题，提炼可复用步骤。\n" +
                "Day4: 针对薄弱点做专项训练，完成 1 道限时题，复盘超时原因。\n" +
                "Day5: 进行模拟讲解，口述解题过程，补齐表达短板。\n" +
                "Day6: 完成 1 道进阶题，对比两种解法并总结取舍。\n" +
                "Day7: 做一次 30 分钟小测，整理错题并形成下周目标。";
    }

    private String normalizeFirstQuestion(String raw, String topic) {
        if (raw == null || raw.isBlank()) {
            return buildFallbackFirstQuestion(topic);
        }

        // 尝试 JSON 解析：如果 LLM 返回了 JSON 格式，直接提取 question 字段
        StructuredOutputResult<JsonNode> parseResult = structuredOutputParser.parseTree(raw.trim(), null, null);
        if (parseResult.success()) {
            JsonNode node = parseResult.data();
            if (node != null && node.isObject()) {
                String q = node.path("question").asText("");
                if (!q.isBlank()) {
                    return truncate(q.trim(), 200);
                }
            }
        }

        String normalized = raw.replace("\r\n", "\n")
                .replace("**", "")
                .replace("`", "")
                .trim();
        String[] lines = normalized.split("\\n");

        // 第一轮：找含问号的行（排除答案/解析/元信息行）
        for (String line : lines) {
            String candidate = line == null ? "" : line.trim();
            if (candidate.isBlank()) {
                continue;
            }
            candidate = candidate
                    .replaceAll("^[\\-•*\\d.\\s]+", "")
                    .replaceAll("^题目[：:]", "")
                    .replaceAll("^第一题[：:]", "")
                    .trim();
            if (isMetaOrAnswerLine(candidate)) {
                continue;
            }
            if (candidate.contains("？") || candidate.contains("?")) {
                return truncateToQuestion(candidate);
            }
        }

        // 第二轮：取第一个非元信息、非答案的非空行
        for (String line : lines) {
            String candidate = line == null ? "" : line.trim();
            if (candidate.isBlank()) {
                continue;
            }
            candidate = candidate
                    .replaceAll("^[\\-•*\\d.\\s]+", "")
                    .replaceAll("^题目[：:]", "")
                    .replaceAll("^第一题[：:]", "")
                    .trim();
            if (isMetaOrAnswerLine(candidate)) {
                continue;
            }
            if (!candidate.isBlank()) {
                return truncate(candidate, 140);
            }
        }
        return buildFallbackFirstQuestion(topic);
    }

    /**
     * 判断是否为元信息行或答案/解析行，应从首题输出中过滤掉。
     */
    private boolean isMetaOrAnswerLine(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        // 元信息前缀
        if (line.startsWith("出题依据") || line.startsWith("策略提示") || line.startsWith("后续建议")) {
            return true;
        }
        // 答案/解析关键词（行首或包含）
        String[] answerKeywords = {"答案", "解答", "参考答案", "解析", "正确答案", "答：", "答:",
                "分析：", "分析:", "解题", "思路", "要点", "考察", "知识点"};
        for (String keyword : answerKeywords) {
            if (line.startsWith(keyword)) {
                return true;
            }
        }
        // 模拟对话格式：候选人/面试者/应聘者的回答行
        String[] roleKeywords = {"候选人：", "候选人:", "面试者：", "面试者:", "应聘者：", "应聘者:",
                "求职者：", "求职者:", "回答：", "回答:", "A：", "A:"};
        for (String keyword : roleKeywords) {
            if (line.startsWith(keyword)) {
                return true;
            }
        }
        // 模拟的后续题目标记（第二题、第三题、Q2、Q3 等）
        if (line.matches("^第[二三四五六七八九十\\d]+题[：:].*") || line.matches("^Q[2-9]\\d*[：:.].*")) {
            return true;
        }
        return false;
    }

    private String truncateToQuestion(String text) {
        int endCn = text.lastIndexOf('？');
        int endEn = text.lastIndexOf('?');
        int end = Math.max(endCn, endEn);
        if (end < 0) {
            return truncate(text, 140);
        }
        return text.substring(0, end + 1).trim();
    }

    private String safeSkillText(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return content.trim();
    }

    private String resolveQuestionStrategySummary(String stage,
                                                  String topic,
                                                  String question,
                                                  String difficultyLevel,
                                                  String followUpState,
                                                  String profileSnapshot,
                                                  String resumeContent,
                                                  boolean skipIntro) {
        SkillExecutionResult result = skillOrchestrator.execute(
                "question-strategy",
                new SkillExecutionContext(
                        RAGTraceContext.getTraceId(),
                        "rag-service",
                        Map.of(
                                "stage", stage == null ? "" : stage,
                                "topic", topic == null ? "" : topic,
                                "question", question == null ? "" : question,
                                "difficultyLevel", difficultyLevel == null ? "" : difficultyLevel,
                                "followUpState", followUpState == null ? "" : followUpState,
                                "profileSnapshot", profileSnapshot == null ? "" : profileSnapshot,
                                "resumeContent", resumeContent == null ? "" : resumeContent,
                                "skipIntro", skipIntro
                        ),
                        skillOrchestrator.newBudget()
                )
        );
        return result.succeeded() ? result.textOutput("strategySummary") : "";
    }

    private String mergeSkillGuidance(String skillBlock, String strategySummary) {
        String normalizedSkillBlock = skillBlock == null ? "" : skillBlock.trim();
        String normalizedStrategySummary = strategySummary == null ? "" : strategySummary.trim();
        if (normalizedSkillBlock.isBlank()) {
            return normalizedStrategySummary;
        }
        if (normalizedStrategySummary.isBlank()) {
            return normalizedSkillBlock;
        }
        return normalizedSkillBlock + "\n\n" + normalizedStrategySummary;
    }

    private String resolveLearningPlanSummary(String topic,
                                              String weakPoint,
                                              String recentPerformance) {
        SkillExecutionResult result = skillOrchestrator.execute(
                "personalized-learning-planner",
                new SkillExecutionContext(
                        RAGTraceContext.getTraceId(),
                        "rag-service",
                        Map.of(
                                "topic", topic == null ? "" : topic,
                                "weakPoint", weakPoint == null ? "" : weakPoint,
                                "recentPerformance", recentPerformance == null ? "" : recentPerformance
                        ),
                        skillOrchestrator.newBudget()
                )
        );
        return result.succeeded() ? result.textOutput("planningSummary") : "";
    }

    private String resolveFinalReportSummary(String topic,
                                             List<KnowledgeInterviewReportEntry> history,
                                             String targetedSuggestion,
                                             String rollingSummary) {
        SkillExecutionResult result = skillOrchestrator.execute(
                "interview-report-generator",
                new SkillExecutionContext(
                        RAGTraceContext.getTraceId(),
                        "rag-service",
                        Map.of(
                                "topic", topic == null ? "" : topic,
                                "history", toReportSkillHistory(history),
                                "targetedSuggestion", targetedSuggestion == null ? "" : targetedSuggestion,
                                "rollingSummary", rollingSummary == null ? "" : rollingSummary
                        ),
                        skillOrchestrator.newBudget()
                )
        );
        return result.succeeded() ? result.textOutput("reportSummary") : "";
    }

    private List<Map<String, Object>> toReportSkillHistory(List<KnowledgeInterviewReportEntry> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return history.stream()
                .map(item -> Map.<String, Object>of(
                        "question", item.question(),
                        "answer", item.answer(),
                        "score", item.score(),
                        "feedback", item.feedback()
                ))
                .toList();
    }

    /**
     * 检索知识包（RAG 中的“证据载体”）。
     *
     * <p>该对象用于在“检索阶段”与“生成/评估阶段”之间传递必要的信息：</p>
     * <ul>
     *     <li>retrievalQuery：改写后的检索 query（用于观测与复现）</li>
     *     <li>retrievedDocs：本地召回的文档片段（用于后续 evidence/trace 细节展示）</li>
     *     <li>context：最终注入提示词的上下文（可能是本地拼接或 Web fallback 拼接）</li>
     *     <li>retrievalEvidence：带编号的证据目录（citations/conflicts 只能引用编号）</li>
     *     <li>webFallbackUsed：是否触发了 Web fallback（用于观测/评测口径区分）</li>
     * </ul>
     */
    public record KnowledgePacket(
            String retrievalQuery,
            List<Document> retrievedDocs,
            List<ImageResult> retrievedImages,
            String context,
            String imageContext,
            String retrievalEvidence,
            boolean webFallbackUsed
    ) {
        public KnowledgePacket(String retrievalQuery,
                               List<Document> retrievedDocs,
                               String context,
                               String retrievalEvidence,
                               boolean webFallbackUsed) {
            this(retrievalQuery, retrievedDocs, List.of(), context, "", retrievalEvidence, webFallbackUsed);
        }
    }

}
