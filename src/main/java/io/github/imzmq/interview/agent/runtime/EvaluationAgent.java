package io.github.imzmq.interview.agent.runtime;

import io.github.imzmq.interview.interview.domain.Question;
import io.github.imzmq.interview.knowledge.application.RAGService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 评估执行器。
 * 负责调用 RAGService、解析模型输出，并统一生成维度化反馈结构。
 */
@Component
public class EvaluationAgent {

    private final RAGService ragService;
    private final ObjectMapper objectMapper;

    public EvaluationAgent(RAGService ragService, ObjectMapper objectMapper) {
        this.ragService = ragService;
        this.objectMapper = objectMapper;
    }

    public String generateFirstQuestion(String resumeContent, String topic) {
        return ragService.generateFirstQuestion(resumeContent, topic, "暂无历史画像。", false);
    }

    public String generateFirstQuestion(String resumeContent, String topic, String profileSnapshot) {
        return ragService.generateFirstQuestion(resumeContent, topic, profileSnapshot, false);
    }

    public String generateFirstQuestion(String resumeContent, String topic, String profileSnapshot, boolean skipIntro) {
        return ragService.generateFirstQuestion(resumeContent, topic, profileSnapshot, skipIntro);
    }

    public EvaluationResult evaluateAnswer(String topic, String question, String userAnswer, String difficultyLevel, String followUpState, double topicMastery, String profileSnapshot) {
        String raw = ragService.processAnswer(topic, question, userAnswer, difficultyLevel, followUpState, topicMastery, profileSnapshot);
        ParsedEvaluation parsed = parseEvaluation(raw);
        String feedback = enrichFeedback(parsed.feedback(), parsed.accuracy(), parsed.logic(), parsed.depth(), parsed.boundary(), parsed.deductions());
        return new EvaluationResult(parsed.score(), parsed.accuracy(), parsed.logic(), parsed.depth(), parsed.boundary(), parsed.deductions(), parsed.citations(), parsed.conflicts(), feedback, parsed.nextQuestion());
    }

    public LayeredEvaluation evaluateAnswerWithKnowledge(String topic, String question, String userAnswer, String difficultyLevel, String followUpState, double topicMastery, String profileSnapshot, String strategyHint, RAGService.KnowledgePacket packet) {
        // 四层链路下的核心评估入口：将策略提示与知识包一并下发。
        RAGService.EvaluationResult evalResult = ragService.evaluateWithKnowledge(topic, question, userAnswer, difficultyLevel, followUpState, topicMastery, profileSnapshot, strategyHint, packet);
        ParsedEvaluation parsed = parseEvaluation(evalResult.json());
        String feedback = enrichFeedback(parsed.feedback(), parsed.accuracy(), parsed.logic(), parsed.depth(), parsed.boundary(), parsed.deductions());
        EvaluationResult result = new EvaluationResult(parsed.score(), parsed.accuracy(), parsed.logic(), parsed.depth(), parsed.boundary(), parsed.deductions(), parsed.citations(), parsed.conflicts(), feedback, parsed.nextQuestion());
        LayerTrace trace = new LayerTrace(
                packet == null ? "" : packet.retrievalQuery(),
                packet == null || packet.retrievedDocs() == null ? 0 : packet.retrievedDocs().size(),
                packet != null && packet.webFallbackUsed(),
                strategyHint == null ? "" : strategyHint,
                evalResult.inputTokens(),
                evalResult.outputTokens()
        );
        return new LayeredEvaluation(result, trace);
    }

    public FinalReportContent summarize(String topic, List<Question> history, String targetedSuggestion, String rollingSummary) {
        String raw = ragService.generateFinalReport(topic, history, targetedSuggestion, rollingSummary);
        String summary = extractTag(raw, "summary");
        String incomplete = extractTag(raw, "incomplete");
        String weak = extractTag(raw, "weak");
        String wrong = extractTag(raw, "wrong");
        String obsidianUpdates = extractTag(raw, "obsidian_updates");
        String nextFocus = extractTag(raw, "next_focus");
        return new FinalReportContent(summary, incomplete, weak, wrong, obsidianUpdates, nextFocus);
    }

    private int extractIntTag(String text, String tag) {
        try {
            String scoreStr = extractTag(text, tag);
            return Integer.parseInt(scoreStr.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** 将分值钳位到 [0, 100] 范围 */
    private int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private ParsedEvaluation parseEvaluation(String raw) {
        String cleanRaw = raw;
        if (cleanRaw != null) {
            cleanRaw = cleanRaw.trim();
            if (cleanRaw.startsWith("```json")) {
                cleanRaw = cleanRaw.substring(7);
            } else if (cleanRaw.startsWith("```")) {
                cleanRaw = cleanRaw.substring(3);
            }
            if (cleanRaw.endsWith("```")) {
                cleanRaw = cleanRaw.substring(0, cleanRaw.length() - 3);
            }
            cleanRaw = cleanRaw.trim();
        }
        try {
            // 优先解析 JSON 结构，失败时再回退到标签提取，提升兼容性。
            JsonNode node = objectMapper.readTree(cleanRaw);
            int score = clamp(node.path("score").asInt(0));
            int accuracy = clamp(node.path("accuracy").asInt(0));
            int logic = clamp(node.path("logic").asInt(0));
            int depth = clamp(node.path("depth").asInt(0));
            int boundary = clamp(node.path("boundary").asInt(0));
            List<String> deductionItems = new ArrayList<>();
            JsonNode deductionsNode = node.path("deductions");
            if (deductionsNode.isArray()) {
                deductionsNode.forEach(item -> {
                    if (item != null && !item.asText("").isBlank()) {
                        deductionItems.add("- " + item.asText("").trim());
                    }
                });
            }
            String deductions = deductionItems.isEmpty() ? "" : String.join("\n", deductionItems);
            String feedback = node.path("feedback").asText("");
            String nextQuestion = sanitizeNextQuestion(node.path("nextQuestion").asText(""));
            List<String> citations = readTextArray(node.path("citations"));
            List<String> conflicts = readTextArray(node.path("conflicts"));
            // 异常满分检测：全部维度均为 100 视为可疑（可能被 prompt 注入操纵）
            if (score == 100 && accuracy == 100 && logic == 100 && depth == 100 && boundary == 100) {
                score = 95;
                feedback = feedback + "\n[系统提示：检测到全维度满分，已自动修正总分]";
            }
            // 总分与维度均值严重偏离时，用维度均值修正
            int dimAvg = (accuracy + logic + depth + boundary) / 4;
            if (Math.abs(score - dimAvg) > 30) {
                score = dimAvg;
            }
            return new ParsedEvaluation(score, accuracy, logic, depth, boundary, deductions, citations, conflicts, feedback, nextQuestion);
        } catch (Exception ignored) {
            int score = clamp(extractIntTag(raw, "score"));
            int accuracy = clamp(extractIntTag(raw, "accuracy"));
            int logic = clamp(extractIntTag(raw, "logic"));
            int depth = clamp(extractIntTag(raw, "depth"));
            int boundary = clamp(extractIntTag(raw, "boundary"));
            String deductions = extractTag(raw, "deductions");
            String feedback = extractTag(raw, "feedback");
            String nextQuestion = extractTag(raw, "next_question");
            if (nextQuestion.isBlank()) {
                nextQuestion = extractTag(raw, "nextQuestion");
            }
            nextQuestion = sanitizeNextQuestion(nextQuestion);
            return new ParsedEvaluation(score, accuracy, logic, depth, boundary, deductions, List.of(), List.of(), feedback, nextQuestion);
        }
    }

    /**
     * 校验 nextQuestion 格式：拒绝选择题/填空题等客观题格式。
     * 如果检测到 ABCD 选项模式，返回空字符串以触发 fallback 生成新题。
     */
    private String sanitizeNextQuestion(String nextQuestion) {
        if (nextQuestion == null || nextQuestion.isBlank()) {
            return "";
        }
        // 检测选择题特征：包含 A. B. C. 或 A、B、C、或 A) B) C) 等选项模式
        Pattern choicePattern = Pattern.compile("(?m)^\\s*[A-Da-d][.、)）]\\s*\\S");
        if (choicePattern.matcher(nextQuestion).find()) {
            return "";
        }
        // 检测填空题特征：包含 _____ 或 ____
        if (nextQuestion.contains("____")) {
            return "";
        }
        return nextQuestion;
    }

    private List<String> readTextArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            String text = item == null ? "" : item.asText("");
            if (!text.isBlank()) {
                values.add(text.trim());
            }
        });
        return values;
    }

    private String enrichFeedback(String feedback, int accuracy, int logic, int depth, int boundary, String deductions) {
        String safeFeedback = feedback == null ? "" : feedback.trim();
        String safeDeductions = deductions == null || deductions.isBlank() ? "暂无明显扣分项。" : deductions.trim();
        return "维度评分：准确性 " + accuracy +
                " / 逻辑 " + logic +
                " / 深度 " + depth +
                " / 边界 " + boundary +
                "\n扣分理由：\n" + safeDeductions +
                "\n综合评价：\n" + safeFeedback;
    }

    private String extractTag(String text, String tag) {
        Pattern pattern = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    public record EvaluationResult(
            int score,
            int accuracy,
            int logic,
            int depth,
            int boundary,
            String deductions,
            List<String> citations,
            List<String> conflicts,
            String feedback,
            String nextQuestion
    ) {
    }

    public record LayerTrace(
            String retrievalQuery,
            int retrievedCount,
            boolean webFallbackUsed,
            String strategyHint,
            int inputTokens,
            int outputTokens
    ) {
    }

    public record LayeredEvaluation(
            EvaluationResult result,
            LayerTrace trace
    ) {
    }

    private record ParsedEvaluation(
            int score,
            int accuracy,
            int logic,
            int depth,
            int boundary,
            String deductions,
            List<String> citations,
            List<String> conflicts,
            String feedback,
            String nextQuestion
    ) {
    }

    public record FinalReportContent(
            String summary,
            String incomplete,
            String weak,
            String wrong,
            String obsidianUpdates,
            String nextFocus
    ) {
    }
}





