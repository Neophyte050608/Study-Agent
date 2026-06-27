package io.github.imzmq.interview.knowledge.application.coding;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.imzmq.interview.agent.application.AgentSkillService;
import io.github.imzmq.interview.agent.runtime.CodingPracticeAgent;
import io.github.imzmq.interview.chat.application.JsonResult;
import io.github.imzmq.interview.chat.application.LlmJsonParser;
import io.github.imzmq.interview.chat.application.PromptManager;
import io.github.imzmq.interview.knowledge.application.RAGService;
import io.github.imzmq.interview.modelrouting.core.ModelRouteType;
import io.github.imzmq.interview.modelrouting.core.RoutingChatService;
import io.github.imzmq.interview.modelrouting.core.TimeoutHint;
import io.github.imzmq.interview.observability.core.RAGTraceContext;
import io.github.imzmq.interview.skill.core.SkillExecutionContext;
import io.github.imzmq.interview.skill.core.SkillExecutionResult;
import io.github.imzmq.interview.skill.runtime.SkillOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 编程练习服务。
 *
 * <p>负责刷题题目生成、批量选择题生成、答案评估、下一题生成及对应 fallback。</p>
 */
@Service
public class CodingPracticeService {

    private static final Logger logger = LoggerFactory.getLogger(CodingPracticeService.class);
    private static final Pattern RAW_API_KEY_PATTERN = Pattern.compile("(?i)(\"?api[-_ ]?key\"?\\s*[:=]\\s*\"?)([^\",\\s]+)");
    private static final Pattern AUTHORIZATION_PATTERN = Pattern.compile("(?i)(authorization\\s*[:=]\\s*bearer\\s+)([A-Za-z0-9._-]{8,})");
    private static final Pattern LONG_TOKEN_PATTERN = Pattern.compile("\\b[A-Za-z0-9._-]{32,}\\b");

    private final RoutingChatService routingChatService;
    private final AgentSkillService agentSkillService;
    private final PromptManager promptManager;
    private final SkillOrchestrator skillOrchestrator;
    private final LlmJsonParser llmJsonParser;

    public CodingPracticeService(RoutingChatService routingChatService,
                                 AgentSkillService agentSkillService,
                                 PromptManager promptManager,
                                 SkillOrchestrator skillOrchestrator,
                                 LlmJsonParser llmJsonParser) {
        this.routingChatService = routingChatService;
        this.agentSkillService = agentSkillService;
        this.promptManager = promptManager;
        this.skillOrchestrator = skillOrchestrator;
        this.llmJsonParser = llmJsonParser;
    }

    public String generateCodingQuestion(String topic, String difficulty, String profileSnapshot, List<String> excludedTopics) {
        String normalizedTopic = topic == null || topic.isBlank() ? "数组与字符串" : topic.trim();
        String normalizedDifficulty = difficulty == null || difficulty.isBlank() ? "medium" : difficulty.trim().toLowerCase(Locale.ROOT);
        String normalizedQuestionType = normalizeCodingQuestionType(normalizedTopic);
        String skillBlock = resolveCodingSkillBlock(normalizedQuestionType);
        String codingCoachSummary = resolveCodingCoachSummary(
                "generate_question",
                normalizedTopic,
                normalizedDifficulty,
                normalizedQuestionType,
                "",
                "",
                0
        );

        Map<String, Object> params = new HashMap<>();
        params.put("skillBlock", mergeSkillGuidance(skillBlock, codingCoachSummary));
        params.put("topic", buildTopicWithExclusion(normalizedTopic, excludedTopics));
        params.put("difficulty", normalizedDifficulty);
        params.put("questionType", normalizedQuestionType);
        params.put("profileSnapshot", truncate(profileSnapshot, 240));
        PromptManager.PromptPair pair = promptManager.renderSplit("coding-coach", "coding-question", params);

        try {
            logger.debug("====== [CodingPracticeService - generateCodingQuestion] Prompt Template ======");
            logger.debug("{}", pair.userPrompt());
            String raw = callWithRetry(() -> routingChatService.callWithFirstPacketProbeSupplier(
                () -> "",
                pair.systemPrompt(), pair.userPrompt(), ModelRouteType.GENERAL, TimeoutHint.NORMAL, "刷题题目生成"
            ), 1, "刷题题目生成");
            logger.debug("====== [CodingPracticeService - generateCodingQuestion] Response ======");
            logger.debug("{}", raw);
            if (raw == null || raw.isBlank()) {
                return buildFallbackCodingQuestion(normalizedTopic, normalizedDifficulty, normalizedQuestionType);
            }
            return truncate(raw.replace("\r\n", " ").replaceAll("\\s+", " ").trim(), 1200);
        } catch (RuntimeException e) {
            logger.warn("刷题题目生成失败，返回降级题目。原因: {}", summarizeError(e));
            return buildFallbackCodingQuestion(normalizedTopic, normalizedDifficulty, normalizedQuestionType);
        }
    }

    /**
     * 批量生成选择题，返回结构化 QuizQuestion 列表。
     * 使用单次 LLM 调用生成 N 道题目。
     */
    public List<CodingPracticeAgent.QuizQuestion> generateBatchQuiz(
            String topic, String difficulty, int count, String profileSnapshot, List<String> excludedTopics) {
        String normalizedTopic = topic == null || topic.isBlank() ? "Java基础" : topic.trim();
        String normalizedDifficulty = difficulty == null || difficulty.isBlank() ? "medium" : difficulty.trim().toLowerCase(Locale.ROOT);
        String skillBlock = resolveCodingSkillBlock("选择题");
        String codingCoachSummary = resolveCodingCoachSummary(
                "generate_question",
                normalizedTopic,
                normalizedDifficulty,
                "CHOICE",
                "",
                "",
                0
        );

        Map<String, Object> params = new HashMap<>();
        params.put("skillBlock", mergeSkillGuidance(skillBlock, codingCoachSummary));
        params.put("topic", buildTopicWithExclusion(normalizedTopic, excludedTopics));
        params.put("difficulty", normalizedDifficulty);
        params.put("count", String.valueOf(Math.min(count, 10)));
        params.put("questionType", "选择题");
        params.put("profileSnapshot", truncate(profileSnapshot, 240));

        String sp;
        String up;
        try {
            PromptManager.PromptPair pair = promptManager.renderSplit("coding-coach", "batch-quiz-question", params);
            sp = pair.systemPrompt();
            up = pair.userPrompt();
        } catch (Exception e) {
            sp = "你是一位专业的技术面试出题官。";
            up = buildInlineBatchQuizPrompt(buildTopicWithExclusion(normalizedTopic, excludedTopics), normalizedDifficulty, count);
        }

        final String finalSystemPrompt = sp;
        final String finalUserPrompt = up;

        try {
            String raw = callWithRetry(() -> routingChatService.callWithFirstPacketProbeSupplier(
                () -> "[]",
                finalSystemPrompt, finalUserPrompt, ModelRouteType.GENERAL, TimeoutHint.NORMAL, "批量选择题生成"
            ), 1, "批量选择题生成");

            if (raw == null || raw.isBlank()) {
                return fallbackBatchQuiz(normalizedTopic, normalizedDifficulty, count, profileSnapshot, excludedTopics);
            }

            JsonResult<JsonNode> parseResult = llmJsonParser.parseTree(raw, null, null);
            if (!parseResult.success() || !parseResult.data().isArray() || parseResult.data().isEmpty()) {
                return fallbackBatchQuiz(normalizedTopic, normalizedDifficulty, count, profileSnapshot, excludedTopics);
            }
            JsonNode rootNode = parseResult.data();

            List<CodingPracticeAgent.QuizQuestion> questions = new ArrayList<>();
            for (JsonNode element : rootNode) {
                List<String> options = new ArrayList<>();
                JsonNode optsNode = element.path("options");
                if (optsNode.isArray()) {
                    for (JsonNode opt : optsNode) {
                        options.add(opt.asText());
                    }
                }
                questions.add(new CodingPracticeAgent.QuizQuestion(
                    element.path("index").asInt(0),
                    element.path("stem").asText(""),
                    options,
                    element.path("correctAnswer").asText(""),
                    element.path("explanation").asText("")
                ));
            }

            if (questions.isEmpty()) {
                return fallbackBatchQuiz(normalizedTopic, normalizedDifficulty, count, profileSnapshot, excludedTopics);
            }

            List<CodingPracticeAgent.QuizQuestion> result = new ArrayList<>();
            for (int i = 0; i < questions.size(); i++) {
                CodingPracticeAgent.QuizQuestion q = questions.get(i);
                result.add(new CodingPracticeAgent.QuizQuestion(
                    i + 1, q.stem(), q.options(), q.correctAnswer(), q.explanation()));
            }
            return result;
        } catch (Exception e) {
            logger.warn("批量选择题生成失败，降级逐题生成。原因: {}", summarizeError(e));
            return fallbackBatchQuiz(normalizedTopic, normalizedDifficulty, count, profileSnapshot, excludedTopics);
        }
    }

    /**
     * 降级方案：逐题调用 generateCodingQuestion 生成（无正确答案和解析）。
     */
    private List<CodingPracticeAgent.QuizQuestion> fallbackBatchQuiz(
            String topic, String difficulty, int count, String profileSnapshot, List<String> excludedTopics) {
        List<CodingPracticeAgent.QuizQuestion> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String questionText = generateCodingQuestion(topic + "（选择题）", difficulty, profileSnapshot, excludedTopics);
            result.add(new CodingPracticeAgent.QuizQuestion(
                i + 1, questionText, List.of("A. 请参考题目", "B. 请参考题目", "C. 请参考题目", "D. 请参考题目"),
                "A", "该题目由降级方案生成，暂无解析。请根据题干自行判断。"));
        }
        return result;
    }

    /**
     * 内联 prompt（当 batch-quiz-question 模板不存在时使用）。
     */
    private String buildInlineBatchQuizPrompt(String topic, String difficulty, int count) {
        return "请生成 " + count + " 道关于「" + topic + "」的" + difficulty + "难度选择题。\n\n"
            + "严格按以下 JSON 格式输出（不要输出其他内容）：\n"
            + "```json\n"
            + "[\n"
            + "  {\n"
            + "    \"index\": 1,\n"
            + "    \"stem\": \"题目描述\",\n"
            + "    \"options\": [\"A. 选项1\", \"B. 选项2\", \"C. 选项3\", \"D. 选项4\"],\n"
            + "    \"correctAnswer\": \"B\",\n"
            + "    \"explanation\": \"详细解析\"\n"
            + "  }\n"
            + "]\n"
            + "```\n"
            + "要求：\n"
            + "1. 每题必须有4个选项（A/B/C/D）\n"
            + "2. correctAnswer 只能是 A/B/C/D 中的一个字母\n"
            + "3. explanation 要详细解释为什么选择该答案\n"
            + "4. 题目要有区分度，难度符合 " + difficulty + " 级别\n"
            + "5. 只输出 JSON 数组，不要有多余文字";
    }

    public RAGService.CodingAssessment evaluateCodingAnswer(String topic, String difficulty, String question, String answer) {
        String normalizedTopic = topic == null || topic.isBlank() ? "算法" : topic.trim();
        String normalizedDifficulty = difficulty == null || difficulty.isBlank() ? "medium" : difficulty.trim().toLowerCase(Locale.ROOT);
        String safeQuestion = question == null ? "" : question.trim();
        String safeAnswer = answer == null ? "" : answer.trim();
        if (safeAnswer.isBlank()) {
            return new RAGService.CodingAssessment(20, "未提供有效答案。", "先补充完整思路，再给出复杂度分析。", fallbackNextCodingQuestion(normalizedTopic, 20, normalizeCodingQuestionType(normalizedTopic)));
        }
        String normalizedQuestionType = normalizeCodingQuestionType(normalizedTopic);
        String skillBlock = resolveCodingSkillBlock(normalizedQuestionType);
        String codingCoachSummary = resolveCodingCoachSummary(
                "evaluate_code",
                normalizedTopic,
                normalizedDifficulty,
                normalizedQuestionType,
                safeQuestion,
                safeAnswer,
                0
        );

        Map<String, Object> params = new HashMap<>();
        params.put("skillBlock", mergeSkillGuidance(skillBlock, codingCoachSummary));
        params.put("topic", normalizedTopic);
        params.put("difficulty", normalizedDifficulty);
        params.put("questionType", normalizedQuestionType);
        params.put("question", truncate(safeQuestion, 1200));
        params.put("answer", truncate(safeAnswer, 800));
        PromptManager.PromptPair pair = promptManager.renderSplit("coding-coach", "coding-evaluation", params);

        try {
            logger.debug("====== [CodingPracticeService - evaluateCodingAnswer] Prompt Template ======");
            logger.debug("{}", pair.userPrompt());
            String raw = callWithRetry(() -> routingChatService.callWithFirstPacketProbeSupplier(
                () -> "{\"score\":0,\"feedback\":\"评估超时\"}",
                pair.systemPrompt(), pair.userPrompt(), ModelRouteType.THINKING, TimeoutHint.SLOW, "刷题答案评估"
            ), 1, "刷题答案评估");
            logger.debug("====== [CodingPracticeService - evaluateCodingAnswer] Response ======");
            logger.debug("{}", raw);
            JsonResult<JsonNode> parseResult = llmJsonParser.parseTree(raw, null, null);
            if (!parseResult.success()) {
                throw new RuntimeException("Coding evaluation JSON parse failed: " + parseResult.failureReason());
            }
            JsonNode node = parseResult.data();
            int score = node.path("score").asInt(0);
            String feedback = node.path("feedback").asText("建议补充完整思路并说明复杂度。");
            String nextHint = node.path("nextHint").asText("请优先补充边界条件与复杂度分析。");
            String nextQuestion = node.path("nextQuestion").asText("");
            if (nextQuestion == null || nextQuestion.isBlank()) {
                nextQuestion = generateNextCodingQuestion(normalizedTopic, normalizedDifficulty, safeQuestion, safeAnswer, score);
            }
            return new RAGService.CodingAssessment(Math.max(0, Math.min(score, 100)), feedback, nextHint, nextQuestion == null ? "" : nextQuestion);
        } catch (Exception e) {
            logger.warn("刷题答案评估失败，返回降级评分。原因: {}", summarizeError(e));
            return fallbackCodingAssessment(safeAnswer, normalizedQuestionType, normalizedTopic);
        }
    }

    public String generateNextCodingQuestion(String topic, String difficulty, String question, String answer, int score) {
        String normalizedQuestionType = normalizeCodingQuestionType(topic);
        String skillBlock = resolveCodingSkillBlock(normalizedQuestionType);
        String codingCoachSummary = resolveCodingCoachSummary(
                "generate_follow_up",
                topic == null ? "算法" : topic,
                difficulty == null ? "medium" : difficulty,
                normalizedQuestionType,
                question,
                answer,
                score
        );

        Map<String, Object> params = new HashMap<>();
        params.put("skillBlock", mergeSkillGuidance(skillBlock, codingCoachSummary));
        params.put("topic", topic == null ? "算法" : topic);
        params.put("difficulty", difficulty == null ? "medium" : difficulty);
        params.put("questionType", normalizedQuestionType);
        params.put("question", truncate(question, 240));
        params.put("answer", truncate(answer, 500));
        params.put("score", String.valueOf(score));
        PromptManager.PromptPair pair = promptManager.renderSplit("coding-coach", "coding-next-question", params);

        try {
            String raw = callWithRetry(() -> routingChatService.callWithFirstPacketProbeSupplier(
                () -> "",
                pair.systemPrompt(), pair.userPrompt(), ModelRouteType.GENERAL, TimeoutHint.NORMAL, "刷题下一题生成"
            ), 1, "刷题下一题生成");
            if (raw == null || raw.isBlank()) {
                return fallbackNextCodingQuestion(topic, score, normalizedQuestionType);
            }
            return truncate(raw.replace("\r\n", " ").replaceAll("\\s+", " ").trim(), 1200);
        } catch (RuntimeException e) {
            logger.warn("刷题下一题生成失败，返回降级问题。原因: {}", summarizeError(e));
            return fallbackNextCodingQuestion(topic, score, normalizedQuestionType);
        }
    }

    private String buildFallbackCodingQuestion(String topic, String difficulty, String questionType) {
        if ("CHOICE".equals(questionType)) {
            return "请生成一道" + difficulty + "难度的「" + topic + "」选择题，包含 4 个选项，不要输出答案。";
        }
        if ("FILL".equals(questionType)) {
            return "请生成一道" + difficulty + "难度的「" + topic + "」填空题，给出题干与必要约束，不要输出答案。";
        }
        if ("SCENARIO".equals(questionType)) {
            return "请生成一道" + difficulty + "难度的「" + topic + "」场景题，要求贴近真实工程情境，不要输出答案。";
        }
        return "请完成一道" + difficulty + "难度的「" + topic + "」算法题：给定整数数组与目标值，返回两数之和等于目标值的下标，并说明时间复杂度与空间复杂度。";
    }

    private String fallbackNextCodingQuestion(String topic, int score, String questionType) {
        String safeTopic = topic == null || topic.isBlank() ? "数组与字符串" : topic;
        if ("CHOICE".equals(questionType)) {
            return "请继续生成一道「" + safeTopic + "」选择题，难度参考当前得分，包含 4 个选项且不要输出答案。";
        }
        if ("FILL".equals(questionType)) {
            return "请继续生成一道「" + safeTopic + "」填空题，难度参考当前得分，给出题干与必要约束。";
        }
        if ("SCENARIO".equals(questionType)) {
            return "请继续生成一道「" + safeTopic + "」场景题，难度参考当前得分，聚焦真实工程情境。";
        }
        if (score < 60) {
            return "请给出一道「" + safeTopic + "」基础题的完整暴力解，并说明如何优化到更低时间复杂度。";
        }
        if (score < 80) {
            return "请继续完成一道「" + safeTopic + "」中等题，并重点说明边界条件和复杂度推导。";
        }
        return "请完成一道「" + safeTopic + "」进阶题，并比较两种不同解法的 trade-off。";
    }

    private RAGService.CodingAssessment fallbackCodingAssessment(String answer, String questionType, String topic) {
        String lower = answer == null ? "" : answer.toLowerCase(Locale.ROOT);
        if (!"ALGORITHM".equals(questionType)) {
            int score = lower.length() > 40 ? 68 : 48;
            return new RAGService.CodingAssessment(
                    score,
                    "答案已提交，但评估服务暂不可用。建议补充关键判断条件、约束与边界情况。",
                    "请补充你对题干条件的理解，并说明最核心的判断依据。",
                    fallbackNextCodingQuestion(topic, score, questionType)
            );
        }
        int score = 35;
        if (lower.contains("o(")) {
            score += 20;
        }
        if (lower.contains("for") || lower.contains("while")) {
            score += 15;
        }
        if (lower.contains("hashmap") || lower.contains("map")) {
            score += 15;
        }
        if (lower.contains("边界") || lower.contains("null") || lower.contains("空")) {
            score += 10;
        }
        score = Math.min(score, 90);
        String feedback = score < 60 ? "题解描述不完整，建议补充步骤与边界场景。"
                : "题解基本可用，建议进一步优化复杂度论证。";
        String nextHint = score < 60 ? "先给出可运行解法，再补充复杂度。"
                : "尝试提供另一种实现并比较 trade-off。";
        return new RAGService.CodingAssessment(score, feedback, nextHint, fallbackNextCodingQuestion("算法", score, "ALGORITHM"));
    }

    private String resolveCodingSkillBlock(String questionType) {
        if ("ALGORITHM".equals(questionType)) {
            return safeSkillText(agentSkillService.resolveSkillBlock("coding-interview-coach"));
        }
        return "";
    }

    private String normalizeCodingQuestionType(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (normalized.contains("选择") || normalized.contains("choice") || normalized.contains("单选") || normalized.contains("多选")) {
            return "CHOICE";
        }
        if (normalized.contains("填空") || normalized.contains("fill") || normalized.contains("补全")) {
            return "FILL";
        }
        if (normalized.contains("场景") || normalized.contains("scenario")) {
            return "SCENARIO";
        }
        return "ALGORITHM";
    }

    private String resolveCodingCoachSummary(String taskType,
                                             String topic,
                                             String difficulty,
                                             String questionType,
                                             String question,
                                             String answer,
                                             int score) {
        SkillExecutionResult result = skillOrchestrator.execute(
                "coding-interview-coach",
                new SkillExecutionContext(
                        RAGTraceContext.getTraceId(),
                        "coding-practice-service",
                        Map.of(
                                "taskType", taskType == null ? "" : taskType,
                                "topic", topic == null ? "" : topic,
                                "difficulty", difficulty == null ? "" : difficulty,
                                "questionType", questionType == null ? "" : questionType,
                                "question", question == null ? "" : question,
                                "answer", answer == null ? "" : answer,
                                "score", score
                        ),
                        skillOrchestrator.newBudget()
                )
        );
        return result.succeeded() ? result.textOutput("coachingSummary") : "";
    }

    private String buildTopicWithExclusion(String topic, List<String> excludedTopics) {
        if (excludedTopics == null || excludedTopics.isEmpty()) {
            return topic;
        }
        return topic + "（注意：不得涉及以下知识点：" + String.join("、", excludedTopics) + "）";
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

    private String safeSkillText(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return content.trim();
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxLength) + "...";
    }

    private String compactMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return message;
    }

    private String summarizeError(Throwable throwable) {
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
}
