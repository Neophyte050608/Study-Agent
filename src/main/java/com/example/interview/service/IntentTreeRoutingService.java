package com.example.interview.service;

import com.example.interview.config.IntentTreeProperties;
import com.example.interview.intent.IntentCandidate;
import com.example.interview.intent.IntentRoutingDecision;
import com.example.interview.intent.IntentTreeNode;
import com.example.interview.modelrouting.ModelRouteType;
import com.example.interview.modelrouting.ModelRoutingProperties;
import com.example.interview.modelrouting.RoutingChatService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class IntentTreeRoutingService {

    private static final Logger logger = LoggerFactory.getLogger(IntentTreeRoutingService.class);

    private final PromptManager promptManager;
    private final IntentTreeProperties properties;
    private final IntentTreeService intentTreeService;
    private final IntentSlotRefineCaseService intentSlotRefineCaseService;
    private final RoutingChatService routingChatService;
    private final String intentPreferredModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IntentTreeRoutingService(
            PromptManager promptManager,
            IntentTreeProperties properties,
            IntentTreeService intentTreeService,
            IntentSlotRefineCaseService intentSlotRefineCaseService,
            ModelRoutingProperties modelRoutingProperties,
            RoutingChatService routingChatService
    ) {
        this.promptManager = promptManager;
        this.properties = properties;
        this.intentTreeService = intentTreeService;
        this.intentSlotRefineCaseService = intentSlotRefineCaseService;
        this.routingChatService = routingChatService;
        this.intentPreferredModel = modelRoutingProperties.getIntentModel();
    }

    /**
     * 判断意图树路由功能是否启用。
     * @return 如果启用了意图树路由则返回 true，否则返回 false
     */
    public boolean enabled() {
        return properties.isEnabled();
    }

    /**
     * 获取主动澄清状态的有效保留时间（分钟）。
     * 当系统向用户发起意图澄清后，在此时效内用户的回复将被作为澄清选项处理。
     * @return 存活时间（分钟）
     */
    public long clarificationTtlMinutes() {
        return properties.getClarificationTtlMinutes();
    }

    /**
     * 核心意图路由逻辑。
     * 根据用户输入和历史记录，利用意图树进行分类，并判定是否需要主动向用户发起澄清。
     * @param query 用户当前输入
     * @param history 对话历史
     * @return 意图路由决策 (IntentRoutingDecision)，包含任务类型、置信度及是否需要澄清等状态
     */
    public IntentRoutingDecision route(String query, String history) {
        if (query == null || query.isBlank()) {
            return IntentRoutingDecision.fallback();
        }
        List<IntentTreeNode> leafIntents = loadLeafIntents();
        if (leafIntents.isEmpty()) {
            return IntentRoutingDecision.fallback();
        }
        try {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("query", query);
            vars.put("history", history == null ? "" : history);
            vars.put("leafIntents", leafIntents);
            vars.put("confidenceThreshold", properties.getConfidenceThreshold());
            vars.put("minGap", properties.getMinGap());
            vars.put("ambiguityRatio", properties.getAmbiguityRatio());
            PromptManager.PromptPair pair = promptManager.renderSplit("router", "intent-tree-classifier", vars);
            String response = routingChatService.call(
                    pair.systemPrompt(), pair.userPrompt(), ModelRouteType.GENERAL, intentPreferredModel, "意图树分类");
            // 解析并归一化模型返回结果
            return normalizeDecision(response, query, history);
        } catch (Exception ex) {
            if (properties.isFallbackToLegacyTaskRouter()) {
                return IntentRoutingDecision.fallback();
            }
            return new IntentRoutingDecision(
                    false, "UNKNOWN", 0D, "intent_tree_failed:" + ex.getMessage(),
                    Map.of(), List.of(), true, "我没有完全理解你的意图，请补充你想进行：面试、刷题还是画像查询？", List.of()
            );
        }
    }

    /**
     * 槽位精炼 (Slot Refinement)
     * 在粗粒度意图识别后，针对特定任务类型，利用动态 Few-shot 样例进行二次参数（槽位）提取。
     * 这能有效缓解大模型在一次性生成复杂 JSON 时的格式不稳定和过度补全（幻觉）问题。
     * 
     * @param taskType 已经识别出的主要任务类型（如 CODING_PRACTICE）
     * @param query 用户的原始输入
     * @param history 对话历史
     * @return 提取出的精确槽位键值对（如 topic, questionType, difficulty）
     */
    public Map<String, Object> refineSlots(String taskType, String query, String history) {
        if (taskType == null || taskType.isBlank() || query == null || query.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("taskType", taskType.toUpperCase());
            vars.put("query", query);
            vars.put("history", history == null ? "" : history);
            vars.put("cases", loadSlotRefineCases(taskType));
            PromptManager.PromptPair pair = promptManager.renderSplit("router", "intent-slot-refine", vars);
            String response = routingChatService.call(
                    pair.systemPrompt(), pair.userPrompt(), ModelRouteType.GENERAL, intentPreferredModel, "意图槽位精炼");
            if (response == null || response.isBlank()) {
                return Map.of();
            }
            String clean = response.replace("```json", "").replace("```", "").trim();
            JsonNode root = objectMapper.readTree(clean);
            JsonNode slotsNode = root.has("slots") ? root.get("slots") : root;
            Map<String, Object> slots = readSlots(slotsNode);
            String questionType = textOf(slots.get("questionType"));
            if (!questionType.isBlank()) {
                String qt = questionType.toUpperCase();
                String type;
                if ("CHOICE".equals(qt) || qt.contains("选择") || qt.contains("单选") || qt.contains("多选")) {
                    type = "选择题";
                } else if ("FILL".equals(qt) || qt.contains("填空") || qt.contains("补全")) {
                    type = "填空题";
                } else if ("SCENARIO".equals(qt) || qt.contains("场景")) {
                    type = "场景题";
                } else {
                    type = "算法题";
                }
                slots.put("type", type);
            }
            return slots;
        } catch (Exception ex) {
            logger.debug("槽位精炼失败, taskType={}: {}", taskType, ex.getMessage());
            return Map.of();
        }
    }

    /**
     * 动态加载并组装用于槽位精炼的 Few-shot（少样本）示例。
     * 为了防止 Token 爆炸和模型注意力分散（Lost in the middle），这里采用动态剪枝策略，
     * 仅加载与当前 taskType 强相关的示例。
     *
     * @param taskType 任务类型
     * @return 匹配的示例列表，每个示例包含 user_query 和 ai_response
     */
    private List<Map<String, String>> loadSlotRefineCases(String taskType) {
        String normalizedTaskType = taskType == null ? "" : taskType.trim().toUpperCase();
        List<Map<String, String>> configuredCases = intentSlotRefineCaseService.listEnabledByTaskType(normalizedTaskType);
        if (!configuredCases.isEmpty()) {
            return configuredCases;
        }
        return defaultSlotRefineCases(normalizedTaskType);
    }

    /**
     * 提供默认的（硬编码兜底）Few-shot 示例。
     * 当外部配置文件或数据库中未提供对应 taskType 的示例时，作为降级使用。
     *
     * @param taskType 任务类型
     * @return 默认示例列表
     */
    private List<Map<String, String>> defaultSlotRefineCases(String taskType) {
        if ("CODING_PRACTICE".equals(taskType)) {
            return List.of(
                    Map.of(
                            "user_query", "来2道Java选择题，简单点",
                            "ai_response", "{\"slots\":{\"topic\":\"Java\",\"questionType\":\"CHOICE\",\"difficulty\":\"easy\",\"count\":2,\"skipIntro\":null,\"mode\":\"\"}}"
                    ),
                    Map.of(
                            "user_query", "刷一道并发算法题",
                            "ai_response", "{\"slots\":{\"topic\":\"并发\",\"questionType\":\"ALGORITHM\",\"difficulty\":\"\",\"count\":1,\"skipIntro\":null,\"mode\":\"\"}}"
                    )
            );
        }
        if ("INTERVIEW_START".equals(taskType)) {
            return List.of(
                    Map.of(
                            "user_query", "来一场Spring Boot面试，跳过自我介绍",
                            "ai_response", "{\"slots\":{\"topic\":\"Spring Boot\",\"questionType\":\"\",\"difficulty\":\"\",\"count\":null,\"skipIntro\":true,\"mode\":\"\"}}"
                    )
            );
        }
        if ("PROFILE_TRAINING_PLAN_QUERY".equals(taskType)) {
            return List.of(
                    Map.of(
                            "user_query", "看下学习计划",
                            "ai_response", "{\"slots\":{\"topic\":\"\",\"questionType\":\"\",\"difficulty\":\"\",\"count\":null,\"skipIntro\":null,\"mode\":\"learning\"}}"
                    )
            );
        }
        return List.of();
    }

    /**
     * 将大模型返回的原始 JSON 字符串解析并归一化为系统的 IntentRoutingDecision 对象。
     * 包含对模型格式幻觉的容错处理（如去除 Markdown 代码块），以及主动澄清逻辑的最终裁定。
     *
     * @param raw 模型返回的原始文本
     * @param query 用户的原始输入
     * @param history 对话历史
     * @return 归一化后的意图决策
     * @throws Exception JSON 解析失败时抛出
     */
    private IntentRoutingDecision normalizeDecision(String raw, String query, String history) throws Exception {
        if (raw == null || raw.isBlank()) {
            return IntentRoutingDecision.fallback();
        }
        String clean = raw.replace("```json", "").replace("```", "").trim();
        JsonNode root = objectMapper.readTree(clean);
        String taskType = readText(root, "taskType").toUpperCase();
        String intentId = readText(root, "intentId");
        double confidence = readScore(root, "confidence");
        String reason = readText(root, "reason");
        Map<String, Object> slots = readSlots(root.get("slots"));
        List<IntentCandidate> candidates = readCandidates(root.get("candidates"));
        boolean askClarification = root.path("askClarification").asBoolean(false);

        if (confidence <= 0 && !candidates.isEmpty()) {
            confidence = candidates.getFirst().score();
        }
        if ((taskType.isBlank() || "UNKNOWN".equals(taskType)) && !candidates.isEmpty()) {
            IntentCandidate top = candidates.getFirst();
            if (top.taskType() != null && !top.taskType().isBlank()) {
                taskType = top.taskType().toUpperCase();
            }
        }

        askClarification = askClarification || shouldClarify(candidates, confidence, slots, taskType);
        List<Map<String, String>> options = readClarificationOptions(root.get("clarificationOptions"), candidates);
        String clarificationQuestion = readText(root, "clarificationQuestion");
        if (askClarification && (clarificationQuestion.isBlank() || options.isEmpty())) {
            clarificationQuestion = buildClarificationQuestion(query, history, candidates);
            if (options.isEmpty()) {
                options = buildOptionsFromCandidates(candidates);
            }
        }
        if (intentId.equalsIgnoreCase("UNKNOWN") && !askClarification) {
            return new IntentRoutingDecision(false, "UNKNOWN", confidence, reason, slots, candidates, false, "", List.of());
        }
        return new IntentRoutingDecision(false, taskType, confidence, reason, slots, candidates, askClarification, clarificationQuestion, options);
    }

    /**
     * 判断是否需要主动向用户澄清意图。
     * 
     * 【设计思考】
     * 1. 为什么不用传统的“置信度最高即命中”？
     *    传统 NLP 或简单的 Prompt 路由遇到模棱两可的输入（如“来两道题”，既可能是选择题也可能是算法题）时，会强行猜一个。
     *    这会导致极其糟糕的用户体验（比如用户想写算法，系统却丢了一道选择题）。
     * 2. 本方案的优势：
     *    引入了“短路澄清机制”。把大模型的不确定性暴露给用户，让用户做选择（1. 算法题 2. 选择题），
     *    这非常符合真实人类对话的逻辑。
     * 
     * 触发条件：
     * 1. 绝对置信度过低（< 阈值）
     * 2. Top1 和 Top2 意图分数相近（差距 < 最小差距阈值，或者比值 >= 模糊率阈值）
     * 3. 特定任务（如刷题）缺少关键槽位（如主题和题型均为空）
     * 
     * @param candidates 候选意图列表（按分数倒序）
     * @param confidence 最终采纳的置信度
     * @param slots 已提取的槽位
     * @param taskType 决定的任务类型
     * @return 是否需要打断流程向用户澄清
     */
    private boolean shouldClarify(List<IntentCandidate> candidates, double confidence, Map<String, Object> slots, String taskType) {
        // 1. 绝对置信度过低，直接澄清
        if (confidence < properties.getConfidenceThreshold()) {
            return true;
        }
        // 2. 意图模糊：前两名候选意图得分相近
        if (candidates.size() >= 2) {
            double top1 = candidates.get(0).score();
            double top2 = candidates.get(1).score();
            if (top1 - top2 < properties.getMinGap()) {
                return true;
            }
            if (top2 / Math.max(top1, 0.0001D) >= properties.getAmbiguityRatio()) {
                return true;
            }
        }
        // 3. 核心槽位缺失（例如刷题没有提供主题和题型），短路阻断并触发澄清
        if ("CODING_PRACTICE".equals(taskType) && textOf(slots.get("topic")).isBlank() && textOf(slots.get("questionType")).isBlank()) {
            return true;
        }
        return false;
    }

    /**
     * 动态生成澄清问题。
     * 调用大模型，根据用户的上下文和候选意图，生成一句自然友好的反问（例如：“您是想刷算法题还是进行模拟面试？”）。
     * 如果大模型生成失败，则提供硬编码的后备澄清文本。
     *
     * @param query 用户输入
     * @param history 对话历史
     * @param candidates 候选意图列表
     * @return 澄清问题文本
     */
    private String buildClarificationQuestion(String query, String history, List<IntentCandidate> candidates) {
        try {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("query", query);
            vars.put("history", history == null ? "" : history);
            vars.put("candidates", candidates.stream().limit(Math.max(1, properties.getMaxCandidates())).toList());
            PromptManager.PromptPair pair = promptManager.renderSplit("router", "intent-clarification", vars);
            String response = routingChatService.call(
                    pair.systemPrompt(), pair.userPrompt(), ModelRouteType.GENERAL, intentPreferredModel, "意图澄清");
            if (response != null && !response.isBlank()) {
                return response.trim();
            }
        } catch (Exception ex) {
            logger.debug("澄清问题生成失败: {}", ex.getMessage());
        }
        if (candidates.isEmpty()) {
            return "我不太确定你的意图，你是想进行模拟面试、刷题练习，还是查询学习计划？";
        }
        StringBuilder sb = new StringBuilder("我不太确定你想做哪一种：\n");
        int index = 1;
        for (IntentCandidate candidate : candidates.stream().limit(Math.max(1, properties.getMaxCandidates())).toList()) {
            sb.append(index++).append(") ").append(candidate.intentId()).append("\n");
        }
        sb.append("回复编号即可。");
        return sb.toString();
    }

    /**
     * 加载当前系统支持的所有“叶子意图（Leaf Intents）”。
     * 优先从数据库/缓存（IntentTreeService）中加载配置的意图树，若为空则回退到代码内置的默认意图。
     *
     * @return 叶子意图节点列表
     */
    private List<IntentTreeNode> loadLeafIntents() {
        List<IntentTreeNode> configured = intentTreeService.loadAllLeafIntents();
        if (!configured.isEmpty()) {
            return configured;
        }
        return defaultLeafIntents();
    }

    /**
     * 提供默认的内置意图树。
     * 包含核心业务：开启面试、生成报告、刷题（选择/填空/算法/场景）、查询学习计划等。
     *
     * @return 默认意图列表
     */
    private List<IntentTreeNode> defaultLeafIntents() {
        return List.of(
                new IntentTreeNode("INTERVIEW.START.GENERAL", "interview/start/general", "开启模拟面试", "开始一场模拟面试", "INTERVIEW_START",
                        List.of("开始一场 Java 面试", "我们来一场 Spring Boot 模拟面试"), List.of("topic", "skipIntro")),
                new IntentTreeNode("INTERVIEW.REPORT.GENERAL", "interview/report/general", "生成面试报告", "生成已结束面试的复盘报告", "INTERVIEW_REPORT",
                        List.of("生成报告", "给我这次面试总结"), List.of("sessionId")),
                new IntentTreeNode("CODING.PRACTICE.CHOICE", "coding/practice/choice", "刷选择题", "编程选择题训练请求", "CODING_PRACTICE",
                        List.of("来一道 Redis 选择题", "出一道 Java 单选题"), List.of("topic", "questionType=CHOICE", "difficulty", "count")),
                new IntentTreeNode("CODING.PRACTICE.FILL", "coding/practice/fill", "刷填空题", "编程填空题训练请求", "CODING_PRACTICE",
                        List.of("来一道 JVM 填空题", "出一道并发补全题"), List.of("topic", "questionType=FILL", "difficulty", "count")),
                new IntentTreeNode("CODING.PRACTICE.ALGORITHM", "coding/practice/algorithm", "刷算法题", "算法实现题训练请求", "CODING_PRACTICE",
                        List.of("来一道数组算法题", "出一道链表算法题"), List.of("topic", "questionType=ALGORITHM", "difficulty", "count")),
                new IntentTreeNode("CODING.PRACTICE.SCENARIO", "coding/practice/scenario", "刷场景题", "工程场景分析题训练请求", "CODING_PRACTICE",
                        List.of("来一道缓存击穿场景题", "出一道 Redis 场景题"), List.of("topic", "difficulty", "count")),
                new IntentTreeNode("PROFILE.TRAINING.QUERY", "profile/training/query", "查询学习计划", "查询学习画像或学习建议", "PROFILE_TRAINING_PLAN_QUERY",
                        List.of("查询我的学习计划", "我最近薄弱点是什么"), List.of("mode")),
                new IntentTreeNode("KNOWLEDGE.QA.GENERAL", "knowledge/qa/general", "知识问答",
                        "用户直接询问技术知识、概念解释、原理分析，如'什么是XXX'、'如何实现XXX'、'XXX和YYY的区别'等", "KNOWLEDGE_QA",
                        List.of("什么是Redis的持久化机制", "Java的垃圾回收是怎么工作的", "解释一下Spring的IOC原理", "HashMap和ConcurrentHashMap的区别"),
                        List.of("topic")),
                new IntentTreeNode("UNKNOWN", "unknown", "未知意图", "无法判定具体业务意图", "UNKNOWN",
                        List.of("你好", "今天天气不错"), List.of())
        );
    }

    /**
     * 从 JSON 节点中读取并解析大模型返回的候选意图列表。
     * 按置信度分数倒序排列，并根据配置的最大候选数（MaxCandidates）进行截断。
     *
     * @param node JSON节点
     * @return 解析后的候选意图列表
     */
    private List<IntentCandidate> readCandidates(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<IntentCandidate> data = new ArrayList<>();
        for (JsonNode item : node) {
            data.add(new IntentCandidate(
                    readText(item, "intentId"),
                    readText(item, "taskType").toUpperCase(),
                    readScore(item, "score"),
                    readText(item, "reason"),
                    readTextArray(item.get("missingSlots"))
            ));
        }
        return data.stream()
                .sorted(Comparator.comparingDouble(IntentCandidate::score).reversed())
                .limit(Math.max(1, properties.getMaxCandidates()))
                .toList();
    }

    /**
     * 从 JSON 节点中提取并规范化业务槽位参数。
     * 包括对布尔值、整数的转换，以及字符串的去空处理。
     *
     * @param slotsNode JSON节点
     * @return 槽位键值对集合
     */
    private Map<String, Object> readSlots(JsonNode slotsNode) {
        if (slotsNode == null || !slotsNode.isObject()) {
            return Map.of();
        }
        Map<String, Object> slots = new LinkedHashMap<>();
        putSlot(slots, "topic", readText(slotsNode, "topic"));
        putSlot(slots, "questionType", readText(slotsNode, "questionType").toUpperCase());
        putSlot(slots, "difficulty", readText(slotsNode, "difficulty").toLowerCase());
        if (slotsNode.has("count") && !slotsNode.get("count").isNull()) {
            JsonNode countNode = slotsNode.get("count");
            if (countNode.isNumber()) {
                slots.put("count", countNode.asInt());
            } else if (countNode.isTextual()) {
                try {
                    int c = Integer.parseInt(countNode.asText().trim());
                    if (c > 0) slots.put("count", c);
                } catch (NumberFormatException ignored) {}
            }
        }
        if (slotsNode.has("skipIntro") && !slotsNode.get("skipIntro").isNull()) {
            slots.put("skipIntro", slotsNode.get("skipIntro").asBoolean());
        }
        putSlot(slots, "mode", readText(slotsNode, "mode"));
        return slots;
    }

    /**
     * 读取澄清选项（选项卡片）。
     * 如果模型明确返回了选项数组，则直接使用；否则根据候选意图列表兜底生成。
     *
     * @param node JSON节点
     * @param candidates 候选意图列表
     * @return 选项列表（含标签、意图ID、提示等）
     */
    private List<Map<String, String>> readClarificationOptions(JsonNode node, List<IntentCandidate> candidates) {
        if (node == null || !node.isArray()) {
            return buildOptionsFromCandidates(candidates);
        }
        List<Map<String, String>> options = new ArrayList<>();
        for (JsonNode item : node) {
            String label = readText(item, "label");
            String intentId = readText(item, "intentId");
            String hint = readText(item, "hint");
            String taskType = readText(item, "taskType").toUpperCase();
            if (label.isBlank() && intentId.isBlank()) {
                continue;
            }
            Map<String, String> option = new LinkedHashMap<>();
            option.put("label", label.isBlank() ? intentId : label);
            option.put("intentId", intentId);
            option.put("hint", hint);
            option.put("taskType", taskType);
            options.add(option);
        }
        return options;
    }

    /**
     * 基于候选意图列表，组装默认的澄清选项卡片。
     * 用于大模型未能按格式返回选项时的兜底保护。
     *
     * @param candidates 候选意图列表
     * @return 选项列表
     */
    private List<Map<String, String>> buildOptionsFromCandidates(List<IntentCandidate> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<Map<String, String>> options = new ArrayList<>();
        for (IntentCandidate candidate : candidates.stream().limit(Math.max(1, properties.getMaxCandidates())).toList()) {
            Map<String, String> option = new LinkedHashMap<>();
            option.put("label", candidate.intentId());
            option.put("intentId", candidate.intentId());
            option.put("hint", candidate.reason());
            option.put("taskType", candidate.taskType());
            options.add(option);
        }
        return options;
    }

    /**
     * 安全地将非空字符串放入槽位 Map 中。
     */
    private void putSlot(Map<String, Object> slots, String key, String value) {
        if (!value.isBlank()) {
            slots.put(key, value);
        }
    }

    /**
     * 安全读取 JSON 节点中的文本字段。
     * 防御 NullNode 及字段缺失。
     */
    private String readText(JsonNode node, String field) {
        if (node == null || field == null || !node.has(field) || node.get(field).isNull()) {
            return "";
        }
        return node.get(field).asText("").trim();
    }

    /**
     * 安全读取 JSON 节点中的数值评分（置信度），并将其截断在 [0, 1] 之间。
     */
    private double readScore(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return 0D;
        }
        return Math.max(0D, Math.min(1D, node.get(field).asDouble(0D)));
    }

    /**
     * 安全读取 JSON 节点中的文本数组（如缺失的槽位列表）。
     */
    private List<String> readTextArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> data = new ArrayList<>();
        for (JsonNode item : node) {
            String text = item.asText("").trim();
            if (!text.isBlank()) {
                data.add(text);
            }
        }
        return data;
    }

    /**
     * 将 Object 安全转换为 String 并去除前后空格。
     */
    private String textOf(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
