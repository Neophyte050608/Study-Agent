package com.example.interview.agent;

import com.example.interview.core.Question;
import com.example.interview.service.RAGService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 评估执行器智能体（EvaluationAgent）。
 * 
 * 核心职责：
 * 1. 协调 RAGService 进行回答评估、题目生成。
 * 2. 解析大模型输出的原始文本（JSON 或自定义标签格式）。
 * 3. 统一生成包含准确性、逻辑、深度、边界等维度的结构化反馈。
 * 4. 维护评估过程中的链路追踪信息（LayerTrace）。
 */
@Component
public class EvaluationAgent {

    private final RAGService ragService;
    private final ObjectMapper objectMapper;

    public EvaluationAgent(RAGService ragService, ObjectMapper objectMapper) {
        this.ragService = ragService;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成面试的首个问题。
     */
    public String generateFirstQuestion(String resumeContent, String topic) {
        return ragService.generateFirstQuestion(resumeContent, topic, "暂无历史画像。");
    }

    /**
     * 根据简历和历史画像生成首个面试题。
     */
    public String generateFirstQuestion(String resumeContent, String topic, String profileSnapshot) {
        return ragService.generateFirstQuestion(resumeContent, topic, profileSnapshot);
    }

    /**
     * 对用户的回答进行多维度评估。
     */
    public EvaluationResult evaluateAnswer(String topic, String question, String userAnswer, String difficultyLevel, String followUpState, double topicMastery, String profileSnapshot) {
        String raw = ragService.processAnswer(topic, question, userAnswer, difficultyLevel, followUpState, topicMastery, profileSnapshot);
        ParsedEvaluation parsed = parseEvaluation(raw);
        String feedback = enrichFeedback(parsed.feedback(), parsed.accuracy(), parsed.logic(), parsed.depth(), parsed.boundary(), parsed.deductions());
        return new EvaluationResult(parsed.score(), parsed.accuracy(), parsed.logic(), parsed.depth(), parsed.boundary(), parsed.deductions(), parsed.citations(), parsed.conflicts(), feedback, parsed.nextQuestion());
    }

    /**
     * 在四层架构（决策、知识、评估、成长）下执行核心评估逻辑。
     * 结合了检索到的知识包（KnowledgePacket）和决策层的策略提示（strategyHint）。
     */
    public LayeredEvaluation evaluateAnswerWithKnowledge(String topic, String question, String userAnswer, String difficultyLevel, String followUpState, double topicMastery, String profileSnapshot, String strategyHint, RAGService.KnowledgePacket packet) {
        // 四层链路下的核心评估入口：将策略提示与知识包一并下发。
        String raw = ragService.evaluateWithKnowledge(topic, question, userAnswer, difficultyLevel, followUpState, topicMastery, profileSnapshot, strategyHint, packet);
        ParsedEvaluation parsed = parseEvaluation(raw);
        String feedback = enrichFeedback(parsed.feedback(), parsed.accuracy(), parsed.logic(), parsed.depth(), parsed.boundary(), parsed.deductions());
        EvaluationResult result = new EvaluationResult(parsed.score(), parsed.accuracy(), parsed.logic(), parsed.depth(), parsed.boundary(), parsed.deductions(), parsed.citations(), parsed.conflicts(), feedback, parsed.nextQuestion());
        
        // 构造执行追踪信息
        LayerTrace trace = new LayerTrace(
                packet == null ? "" : packet.retrievalQuery(),
                packet == null || packet.retrievedDocs() == null ? 0 : packet.retrievedDocs().size(),
                packet != null && packet.webFallbackUsed(),
                strategyHint == null ? "" : strategyHint
        );
        return new LayeredEvaluation(result, trace);
    }

    /**
     * 汇总面试历史，生成最终复盘报告。
     */
    public FinalReportContent summarize(String topic, List<Question> history, String targetedSuggestion) {
        String raw = ragService.generateFinalReport(topic, history, targetedSuggestion);
        String summary = extractTag(raw, "summary");
        String incomplete = extractTag(raw, "incomplete");
        String weak = extractTag(raw, "weak");
        String wrong = extractTag(raw, "wrong");
        String obsidianUpdates = extractTag(raw, "obsidian_updates");
        String nextFocus = extractTag(raw, "next_focus");
        return new FinalReportContent(summary, incomplete, weak, wrong, obsidianUpdates, nextFocus);
    }

    /**
     * 从带标签的文本中提取整数分值。
     */
    private int extractIntTag(String text, String tag) {
        try {
            String scoreStr = extractTag(text, tag);
            return Integer.parseInt(scoreStr.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 解析模型返回的原始字符串，支持 JSON 块和自定义 XML 标签。
     */
    private ParsedEvaluation parseEvaluation(String raw) {
        String cleanRaw = raw;
        if (cleanRaw != null) {
            cleanRaw = cleanRaw.trim();
            // 清理 markdown 代码块标识
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
            // 1. 优先尝试解析 JSON
            JsonNode node = objectMapper.readTree(cleanRaw);
            int score = node.path("score").asInt(0);
            int accuracy = node.path("accuracy").asInt(0);
            int logic = node.path("logic").asInt(0);
            int depth = node.path("depth").asInt(0);
            int boundary = node.path("boundary").asInt(0);
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
            String nextQuestion = node.path("nextQuestion").asText("");
            List<String> citations = readTextArray(node.path("citations"));
            List<String> conflicts = readTextArray(node.path("conflicts"));
            return new ParsedEvaluation(score, accuracy, logic, depth, boundary, deductions, citations, conflicts, feedback, nextQuestion);
        } catch (Exception ignored) {
            // 2. 如果 JSON 解析失败，回退到正则表达式标签提取
            int score = extractIntTag(raw, "score");
            int accuracy = extractIntTag(raw, "accuracy");
            int logic = extractIntTag(raw, "logic");
            int depth = extractIntTag(raw, "depth");
            int boundary = extractIntTag(raw, "boundary");
            String deductions = extractTag(raw, "deductions");
            String feedback = extractTag(raw, "feedback");
            String nextQuestion = extractTag(raw, "next_question");
            if (nextQuestion.isBlank()) {
                nextQuestion = extractTag(raw, "nextQuestion");
            }
            return new ParsedEvaluation(score, accuracy, logic, depth, boundary, deductions, List.of(), List.of(), feedback, nextQuestion);
        }
    }

    /**
     * 从 JSON 数组节点读取字符串列表。
     */
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

    /**
     * 丰富评语信息，将维度得分、扣分项和综合评语拼装。
     */
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

    /**
     * 正则提取 XML 风格标签内容。
     */
    private String extractTag(String text, String tag) {
        Pattern pattern = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    /**
     * 评估结果数据类。
     */
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

    /**
     * 评估链路追踪信息类。
     */
    public record LayerTrace(
            String retrievalQuery,
            int retrievedCount,
            boolean webFallbackUsed,
            String strategyHint
    ) {
    }

    /**
     * 封装结果与追踪信息的复合类。
     */
    public record LayeredEvaluation(
            EvaluationResult result,
            LayerTrace trace
    ) {
    }

    /**
     * 内部解析中间结果类。
     */
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

    /**
     * 复盘报告内容数据类。
     */
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
