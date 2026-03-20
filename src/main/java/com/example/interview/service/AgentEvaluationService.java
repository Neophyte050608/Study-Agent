package com.example.interview.service;

import com.example.interview.agent.task.TaskResponse;
import com.example.interview.agent.task.TaskType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Agent 自动化评测服务。
 * 
 * 该服务提供了一套完整的 Agent 质量评估体系，支持：
 * 1. 运行试验 (Trial)：模拟或真实调用 Agent 并记录其表现。
 * 2. 多维度评分 (Scoring)：结合关键词匹配 (Rule) 和 LLM 语义评估 (Relevancy) 对输出进行打分。
 * 3. 自动迭代优化 (Optimization)：如果初始输出未达标，可利用 LLM 进行自我修正。
 * 4. 链路追踪 (Transcript)：记录完整的对话链路，便于排障和效果分析。
 */
@Service
public class AgentEvaluationService {

    private static final Pattern TOKEN_SPLITTER = Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4e00-\\u9fa5]+");
    /** 试验记录存储，Key 为 trialId */
    private final Map<String, TrialRecord> trialsById = new ConcurrentHashMap<>();
    /** 试验记录排序列表，用于分页展示 */
    private final List<String> trialOrder = new CopyOnWriteArrayList<>();
    private final ChatModel evaluatorChatModel;
    private final ObjectProvider<InterviewService> interviewServiceProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentEvaluationService(
            ObjectProvider<ChatModel> evaluatorChatModelProvider,
            ObjectProvider<InterviewService> interviewServiceProvider
    ) {
        this.evaluatorChatModel = evaluatorChatModelProvider.getIfAvailable();
        this.interviewServiceProvider = interviewServiceProvider;
    }

    /**
     * 运行一次评测试验。
     * 
     * @param operator 操作者 ID，用于记录审计日志
     * @param payload 包含试验参数的 Map，关键字段包括：
     *                - agent: 目标 Agent 名称
     *                - task: 评测任务描述
     *                - expected: 期望的输出结果（用于关键词比对）
     *                - candidateOutput: 可选，手动提供的输出（若提供则跳过执行直接评分）
     *                - scorerType: 评分模式（rule/llm/hybrid）
     *                - executionMode: 执行模式（simulate 模拟/real 真实调用）
     *                - taskType: 任务类型（仅 real 模式有效）
     *                - maxIterations: 最大优化迭代次数
     *                - passThreshold: 合格分数线
     * @return 试验结果摘要，包含评分、状态、对话链路等
     */
    public Map<String, Object> runTrial(String operator, Map<String, Object> payload) {
        String normalizedOperator = operator == null || operator.isBlank() ? "anonymous" : operator.trim();
        String agent = stringOf(payload.get("agent"), "generic-agent");
        String task = stringOf(payload.get("task"), "");
        String expected = stringOf(payload.get("expected"), "");
        String candidateOutput = stringOf(payload.get("candidateOutput"), "");
        String scorerType = normalizeScorerType(stringOf(payload.get("scorerType"), "hybrid"));
        String executionMode = normalizeExecutionMode(stringOf(payload.get("executionMode"), "simulate"));
        String taskTypeName = stringOf(payload.get("taskType"), "");
        int maxIterations = intOf(payload.get("maxIterations"), 1, 1, 6);
        double passThreshold = doubleOf(payload.get("passThreshold"), 80.0, 30.0, 100.0);
        boolean optimize = boolOf(payload.get("optimize"), maxIterations > 1);
        Map<String, Object> input = mapOf(payload.get("input"));
        Map<String, Object> context = mapOf(payload.get("context"));

        // 1. 获取初始执行结果（模拟合成、真实调用或手动输入）
        ExecutionResult executionResult = resolveInitialExecution(
                normalizedOperator,
                agent,
                task,
                input,
                context,
                taskTypeName,
                candidateOutput,
                executionMode
        );
        String output = executionResult.output();
        List<ScoreRecord> evaluationHistory = new ArrayList<>();
        
        // 2. 初始评分
        ScoreRecord score = score(agent, task, expected, output, scorerType);
        evaluationHistory.add(score);
        
        // 3. 自动优化迭代
        int iterations = 1;
        while (optimize && iterations < maxIterations && score.overall() < passThreshold) {
            output = refineOutput(agent, task, expected, output, score, input);
            score = score(agent, task, expected, output, scorerType);
            evaluationHistory.add(score);
            iterations++;
        }
        
        boolean converged = score.overall() >= passThreshold;
        List<TurnRecord> transcript = buildTranscript(agent, task, input, output);
        String now = Instant.now().toString();
        String trialId = "trial-" + UUID.randomUUID();
        
        // 4. 持久化记录
        TrialRecord record = new TrialRecord(
                trialId,
                normalizedOperator,
                agent,
                task,
                expected,
                input,
                transcript,
                score,
                evaluationHistory,
                iterations,
                converged,
                scorerType,
                executionMode,
                executionResult.taskType(),
                "completed",
                now,
                now
        );
        trialsById.put(trialId, record);
        trialOrder.add(trialId);
        trimOldTrials(2000); // 限制记录数量，防止内存溢出
        return toTrialMap(record, true);
    }

    /**
     * 获取评测试验列表。
     */
    public List<Map<String, Object>> listTrials(int limit, String agent, String status) {
        int normalized = limit <= 0 ? 20 : Math.min(limit, 200);
        String normalizedAgent = agent == null ? "" : agent.trim();
        String normalizedStatus = status == null ? "" : status.trim();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = trialOrder.size() - 1; i >= 0 && rows.size() < normalized; i--) {
            TrialRecord record = trialsById.get(trialOrder.get(i));
            if (record == null) {
                continue;
            }
            if (!normalizedAgent.isBlank() && !normalizedAgent.equals(record.agent())) {
                continue;
            }
            if (!normalizedStatus.isBlank() && !normalizedStatus.equals(record.status())) {
                continue;
            }
            rows.add(toTrialMap(record, false));
        }
        return rows;
    }

    /**
     * 获取单个试验详情。
     */
    public Map<String, Object> getTrial(String trialId) {
        TrialRecord record = trialsById.get(trialId);
        if (record == null) {
            return Map.of();
        }
        return toTrialMap(record, true);
    }

    /**
     * 获取试验的完整对话链路。
     */
    public Map<String, Object> getTranscript(String trialId) {
        TrialRecord record = trialsById.get(trialId);
        if (record == null) {
            return Map.of();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("trialId", record.trialId());
        data.put("agent", record.agent());
        data.put("task", record.task());
        data.put("turns", record.transcript());
        data.put("turnCount", record.transcript().size());
        return data;
    }

    /**
     * 列出所有可用的评分器及其权重配置。
     */
    public List<Map<String, Object>> listScorers() {
        return List.of(
                Map.of(
                        "name", "keyword-overlap",
                        "weight", 0.55,
                        "description", "评估 expected 与输出的关键词覆盖率"
                ),
                Map.of(
                        "name", "task-alignment",
                        "weight", 0.30,
                        "description", "评估输出与任务描述的对齐程度"
                ),
                Map.of(
                        "name", "output-format",
                        "weight", 0.15,
                        "description", "评估输出结构完整性与可读性"
                ),
                Map.of(
                        "name", "llm-relevancy",
                        "weight", 0.30,
                        "available", evaluatorChatModel != null,
                        "description", "使用 Spring AI RelevancyEvaluator 做语义相关性评估"
                )
        );
    }

    /**
     * 解析初始执行结果。
     */
    private ExecutionResult resolveInitialExecution(
            String operator,
            String agent,
            String task,
            Map<String, Object> input,
            Map<String, Object> context,
            String taskTypeName,
            String candidateOutput,
            String executionMode
    ) {
        if (!candidateOutput.isBlank()) {
            return new ExecutionResult(candidateOutput, "manual");
        }
        if ("real".equals(executionMode)) {
            return executeRealAgentTrial(operator, agent, task, input, context, taskTypeName);
        }
        return new ExecutionResult(synthesizeOutput(agent, task, input), "simulate");
    }

    /**
     * 真实调用 Agent 接口进行测试。
     */
    private ExecutionResult executeRealAgentTrial(
            String operator,
            String agent,
            String task,
            Map<String, Object> input,
            Map<String, Object> context,
            String taskTypeName
    ) {
        InterviewService interviewService = interviewServiceProvider.getIfAvailable();
        if (interviewService == null) {
            return new ExecutionResult("real mode unavailable: InterviewService not found", "");
        }
        TaskType taskType = parseTaskType(taskTypeName);
        if (taskType == null) {
            taskType = inferTaskTypeByAgent(agent);
        }
        if (taskType == null) {
            return new ExecutionResult("real mode unavailable: cannot resolve taskType for agent " + agent, "");
        }
        Map<String, Object> mergedContext = new LinkedHashMap<>(context);
        mergedContext.putIfAbsent("userId", operator);
        mergedContext.putIfAbsent("source", "agent-eval-trial");
        mergedContext.putIfAbsent("task", task);
        try {
            TaskResponse response = interviewService.dispatchTask(taskType, input, mergedContext);
            if (!response.success()) {
                return new ExecutionResult("task failed: " + response.message(), taskType.name());
            }
            return new ExecutionResult(toJsonText(response.data()), taskType.name());
        } catch (RuntimeException ex) {
            return new ExecutionResult("task invoke error: " + stringOf(ex.getMessage(), "unknown"), taskType.name());
        }
    }

    /**
     * 利用 LLM 优化 Agent 输出。
     */
    private String refineOutput(
            String agent,
            String task,
            String expected,
            String output,
            ScoreRecord score,
            Map<String, Object> input
    ) {
        if (evaluatorChatModel == null) {
            return heuristicRefine(expected, output);
        }
        try {
            ChatClient client = ChatClient.builder(evaluatorChatModel).build();
            String prompt = "你是一个评估优化器。请根据任务、期望结果、当前输出和评分信息，给出改进后的最终输出。\n"
                    + "只输出改进后的内容，不要解释。\n\n"
                    + "agent:\n" + agent + "\n\n"
                    + "task:\n" + task + "\n\n"
                    + "expected:\n" + expected + "\n\n"
                    + "input:\n" + input + "\n\n"
                    + "currentOutput:\n" + output + "\n\n"
                    + "scoreRationale:\n" + score.rationale();
            String refined = client.prompt().user(prompt).call().content();
            return stringOf(refined, output);
        } catch (RuntimeException ex) {
            return heuristicRefine(expected, output);
        }
    }

    /**
     * 启发式优化：如果无法使用 LLM，通过简单的关键词补全进行“伪优化”。
     */
    private String heuristicRefine(String expected, String output) {
        String expectedText = stringOf(expected, "");
        if (expectedText.isBlank()) {
            return output;
        }
        Set<String> expectedTokens = tokenize(expectedText);
        Set<String> outputTokens = tokenize(output);
        List<String> missing = expectedTokens.stream().filter(token -> !outputTokens.contains(token)).limit(5).toList();
        if (missing.isEmpty()) {
            return output;
        }
        return output + "\n补充要点: " + String.join(", ", missing);
    }

    /** 构建试验链路 */
    private List<TurnRecord> buildTranscript(String agent, String task, Map<String, Object> input, String candidateOutput) {
        String now = Instant.now().toString();
        List<TurnRecord> turns = new ArrayList<>();
        turns.add(new TurnRecord(1, "system", "agent=" + agent + ";mode=trial", now));
        turns.add(new TurnRecord(2, "user", "task=" + task + ";input=" + input, now));
        String output = candidateOutput.isBlank() ? synthesizeOutput(agent, task, input) : candidateOutput;
        turns.add(new TurnRecord(3, "assistant", output, now));
        return turns;
    }

    /** 合成模拟输出 */
    private String synthesizeOutput(String agent, String task, Map<String, Object> input) {
        String objective = task.isBlank() ? "完成任务" : task;
        if (input.isEmpty()) {
            return "agent " + agent + " 已完成试跑，输出目标：" + objective;
        }
        return "agent " + agent + " 已完成试跑，输入要点：" + input.keySet() + "，输出目标：" + objective;
    }

    /** 核心打分逻辑 */
    private ScoreRecord score(String agent, String task, String expected, String output, String scorerType) {
        double overlap = overlapScore(expected, output);
        double alignment = overlapScore(task, output);
        double format = formatScore(output);
        double ruleOverall = round(overlap * 0.55 + alignment * 0.30 + format * 0.15);
        Double llmRelevancy = llmRelevancyScore(task, expected, output);
        Boolean llmPass = llmRelevancy == null ? null : llmRelevancy >= 60.0;
        double overall = resolveOverall(ruleOverall, llmRelevancy, scorerType);
        String grade = overall >= 85 ? "A" : overall >= 70 ? "B" : overall >= 55 ? "C" : "D";
        String rationale = "agent=" + agent
                + ", scorerType=" + scorerType
                + ", overlap=" + overlap
                + ", alignment=" + alignment
                + ", format=" + format
                + ", llmRelevancy=" + (llmRelevancy == null ? "NA" : llmRelevancy);
        return new ScoreRecord(overall, overlap, alignment, format, llmRelevancy, llmPass, scorerType, grade, rationale);
    }

    private double resolveOverall(double ruleOverall, Double llmRelevancy, String scorerType) {
        if ("llm".equals(scorerType) && llmRelevancy != null) {
            return llmRelevancy;
        }
        if ("hybrid".equals(scorerType) && llmRelevancy != null) {
            return round(ruleOverall * 0.7 + llmRelevancy * 0.3);
        }
        return ruleOverall;
    }

    /** 利用 Spring AI 评测组件进行语义相关性评分 */
    private Double llmRelevancyScore(String task, String expected, String output) {
        if (evaluatorChatModel == null) {
            return null;
        }
        String query = (task == null ? "" : task.trim()) + "\nExpected:" + (expected == null ? "" : expected.trim());
        EvaluationRequest request = new EvaluationRequest(query, Collections.emptyList(), output == null ? "" : output);
        RelevancyEvaluator evaluator = new RelevancyEvaluator(ChatClient.builder(evaluatorChatModel));
        EvaluationResponse response = evaluator.evaluate(request);
        return round(response.getScore() * 100.0);
    }

    /** 计算关键词重合度得分 */
    private double overlapScore(String expected, String output) {
        Set<String> expectedTokens = tokenize(expected);
        Set<String> outputTokens = tokenize(output);
        if (expectedTokens.isEmpty()) {
            return outputTokens.isEmpty() ? 60.0 : 80.0;
        }
        long hit = expectedTokens.stream().filter(outputTokens::contains).count();
        return round((hit * 100.0) / expectedTokens.size());
    }

    /** 格式规范性得分（检查是否为 JSON 等结构化格式） */
    private double formatScore(String output) {
        if (output == null || output.isBlank()) {
            return 30.0;
        }
        String text = output.trim();
        if ((text.startsWith("{") && text.endsWith("}")) || (text.startsWith("[") && text.endsWith("]"))) {
            return 92.0;
        }
        if (text.length() >= 40) {
            return 84.0;
        }
        return 70.0;
    }

    /** 分词逻辑 */
    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        return TOKEN_SPLITTER.splitAsStream(text)
                .map(String::trim)
                .filter(token -> !token.isBlank())
                .map(String::toLowerCase)
                .collect(java.util.stream.Collectors.toSet());
    }

    private double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private void trimOldTrials(int max) {
        if (trialOrder.size() <= max) {
            return;
        }
        int overflow = trialOrder.size() - max;
        for (int i = 0; i < overflow; i++) {
            String trialId = trialOrder.remove(0);
            trialsById.remove(trialId);
        }
    }

    private Map<String, Object> toTrialMap(TrialRecord record, boolean includeTranscript) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("trialId", record.trialId());
        data.put("operator", record.operator());
        data.put("agent", record.agent());
        data.put("task", record.task());
        data.put("expected", record.expected());
        data.put("input", record.input());
        data.put("status", record.status());
        data.put("startedAt", record.startedAt());
        data.put("endedAt", record.endedAt());
        data.put("score", record.score());
        data.put("scorerType", record.scorerType());
        data.put("executionMode", record.executionMode());
        data.put("taskType", record.taskType());
        data.put("iterations", record.iterations());
        data.put("converged", record.converged());
        data.put("evaluationHistory", record.evaluationHistory());
        if (includeTranscript) {
            data.put("transcript", record.transcript());
            data.put("turnCount", record.transcript().size());
        }
        return data;
    }

    private Map<String, Object> mapOf(Object raw) {
        if (!(raw instanceof Map<?, ?>)) {
            return Map.of();
        }
        Map<?, ?> map = (Map<?, ?>) raw;
        Map<String, Object> data = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            data.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return data;
    }

    private String stringOf(Object raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String text = String.valueOf(raw).trim();
        return text.isBlank() ? fallback : text;
    }

    private String normalizeScorerType(String scorerType) {
        String normalized = scorerType == null ? "" : scorerType.trim().toLowerCase();
        if ("rule".equals(normalized) || "llm".equals(normalized) || "hybrid".equals(normalized)) {
            return normalized;
        }
        return "hybrid";
    }

    private String normalizeExecutionMode(String executionMode) {
        String normalized = executionMode == null ? "" : executionMode.trim().toLowerCase();
        if ("simulate".equals(normalized) || "real".equals(normalized)) {
            return normalized;
        }
        return "simulate";
    }

    private TaskType parseTaskType(String taskTypeName) {
        if (taskTypeName == null || taskTypeName.isBlank()) {
            return null;
        }
        try {
            return TaskType.valueOf(taskTypeName.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** 根据 Agent 名称推断任务类型 */
    private TaskType inferTaskTypeByAgent(String agent) {
        String normalized = agent == null ? "" : agent.trim();
        if ("NoteMakingAgent".equalsIgnoreCase(normalized)) {
            return TaskType.LEARNING_PLAN;
        }
        if ("CodingPracticeAgent".equalsIgnoreCase(normalized)) {
            return TaskType.CODING_PRACTICE;
        }
        if ("InterviewOrchestratorAgent".equalsIgnoreCase(normalized)) {
            return TaskType.INTERVIEW_START;
        }
        return null;
    }

    private int intOf(Object raw, int defaultValue, int min, int max) {
        int parsed = defaultValue;
        if (raw instanceof Number number) {
            parsed = number.intValue();
        } else if (raw instanceof String text && !text.isBlank()) {
            try {
                parsed = Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                parsed = defaultValue;
            }
        }
        return Math.max(min, Math.min(max, parsed));
    }

    private double doubleOf(Object raw, double defaultValue, double min, double max) {
        double parsed = defaultValue;
        if (raw instanceof Number number) {
            parsed = number.doubleValue();
        } else if (raw instanceof String text && !text.isBlank()) {
            try {
                parsed = Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                parsed = defaultValue;
            }
        }
        return Math.max(min, Math.min(max, parsed));
    }

    private boolean boolOf(Object raw, boolean defaultValue) {
        if (raw instanceof Boolean value) {
            return value;
        }
        if (raw instanceof String text && !text.isBlank()) {
            return "true".equalsIgnoreCase(text.trim()) || "1".equals(text.trim());
        }
        return defaultValue;
    }

    private String toJsonText(Object data) {
        if (data == null) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException ex) {
            return String.valueOf(data);
        }
    }

    /** 链路单轮记录 */
    public record TurnRecord(
            int turn,
            String role,
            String content,
            String timestamp
    ) {
    }

    /** 评分结果记录 */
    public record ScoreRecord(
            double overall,
            double expectedOverlap,
            double taskAlignment,
            double outputFormat,
            Double llmRelevancy,
            Boolean llmPass,
            String scorerType,
            String grade,
            String rationale
    ) {
    }

    /** 试验完整记录实体 */
    private record TrialRecord(
            String trialId,
            String operator,
            String agent,
            String task,
            String expected,
            Map<String, Object> input,
            List<TurnRecord> transcript,
            ScoreRecord score,
            List<ScoreRecord> evaluationHistory,
            int iterations,
            boolean converged,
            String scorerType,
            String executionMode,
            String taskType,
            String status,
            String startedAt,
            String endedAt
    ) {
    }

    private record ExecutionResult(
            String output,
            String taskType
    ) {
    }
}
