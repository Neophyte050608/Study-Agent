package com.example.interview.service;

import com.example.interview.config.IntentTreeProperties;
import com.example.interview.intent.IntentCandidate;
import com.example.interview.intent.IntentRoutingDecision;
import com.example.interview.intent.IntentTreeNode;
import com.example.interview.observability.TraceNode;
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
    private static final int LOG_TEXT_LIMIT = 4000;

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

    public boolean enabled() {
        return properties.isEnabled();
    }

    public long clarificationTtlMinutes() {
        return properties.getClarificationTtlMinutes();
    }

    public IntentRoutingDecision route(String query, String history) {
        return route(query, history, null);
    }

    /**
     * 域优先条件下钻路由。
     *
     * @param query   用户输入
     * @param history 对话历史
     * @param domain  PreFilter 提供的域提示（可为 null）
     * @return 意图路由决策
     */
    @TraceNode(type = "INTENT", name = "INTENT_ROUTE")
    public IntentRoutingDecision route(String query, String history, String domain) {
        if (query == null || query.isBlank()) {
            return IntentRoutingDecision.fallback();
        }
        String sanitizedHistory = sanitizeHistoryForIntentRouting(history);
        List<IntentTreeNode> candidateLeaves = resolveCandidateLeaves(domain);
        if (candidateLeaves.isEmpty()) {
            logger.warn("无可用叶子节点, domainHint={}, query={}", domain, truncateForLog(query));
            return routeFailureDecision("no_leaf_candidates");
        }
        try {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("query", query);
            vars.put("history", sanitizedHistory);
            vars.put("leafIntents", toTemplateLeafIntents(candidateLeaves));
            vars.put("confidenceThreshold", properties.getConfidenceThreshold());
            vars.put("minGap", properties.getMinGap());
            vars.put("ambiguityRatio", properties.getAmbiguityRatio());
            vars.put("domainHint", domain == null ? "" : domain);
            PromptManager.PromptPair pair = promptManager.renderSplit("router", "intent-tree-classifier", vars);

            logger.info("统一意图分类请求: preferredModel={}, domainHint={}, query={}, history={}, leafCount={}",
                    intentPreferredModel,
                    domain == null ? "" : domain,
                    truncateForLog(query),
                    truncateForLog(sanitizedHistory),
                    candidateLeaves.size());
            String response = routingChatService.call(
                    pair.systemPrompt(), pair.userPrompt(),
                    ModelRouteType.GENERAL, intentPreferredModel, "统一意图分类");
            logger.info("统一意图分类响应: query={}, rawResponse={}",
                    truncateForLog(query),
                    truncateForLog(response));
            return normalizeDecision(response, query, sanitizedHistory);
        } catch (Exception ex) {
            logger.warn("统一意图分类失败: {}", ex.getMessage());
            return routeFailureDecision(ex.getMessage());
        }
    }

    @TraceNode(type = "INTENT", name = "SLOT_REFINE")
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

    private List<Map<String, String>> loadSlotRefineCases(String taskType) {
        String normalizedTaskType = taskType == null ? "" : taskType.trim().toUpperCase();
        List<Map<String, String>> configuredCases = intentSlotRefineCaseService.listEnabledByTaskType(normalizedTaskType);
        if (!configuredCases.isEmpty()) {
            return configuredCases;
        }
        return defaultSlotRefineCases(normalizedTaskType);
    }

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
        boolean topicSwitch = readBoolean(root, "topicSwitch");
        String dialogAct = readText(root, "dialogAct").toUpperCase();
        double infoNovelty = readScore(root, "infoNovelty");
        boolean infoNoveltyProvided = hasNonNullField(root, "infoNovelty");
        String currentTopic = readText(root, "currentTopic");
        String previousTopic = readText(root, "previousTopic");
        String contextPolicy = normalizeContextPolicy(readText(root, "contextPolicy"), dialogAct, topicSwitch);
        if (currentTopic.isBlank()) {
            currentTopic = extractFallbackTopic(query);
        }
        if (previousTopic.isBlank()) {
            previousTopic = currentTopic;
        }
        if (dialogAct.isBlank()) {
            dialogAct = inferDialogAct(contextPolicy);
        }
        if (!infoNoveltyProvided) {
            infoNovelty = "SWITCH".equals(contextPolicy) ? 0.9D : 0.5D;
        }

        if (confidence <= 0 && !candidates.isEmpty()) {
            confidence = candidates.getFirst().score();
        }
        if ((taskType.isBlank() || "UNKNOWN".equals(taskType)) && !candidates.isEmpty()) {
            IntentCandidate top = candidates.getFirst();
            if (top.taskType() != null && !top.taskType().isBlank()) {
                taskType = top.taskType().toUpperCase();
            }
        }

        boolean askClarification = shouldClarify(candidates, confidence, slots, taskType);
        List<Map<String, String>> options = List.of();
        String clarificationQuestion = "";
        if (askClarification) {
            clarificationQuestion = buildClarificationQuestion(query, history, candidates);
            options = buildOptionsFromCandidates(candidates);
        }
        if (intentId.equalsIgnoreCase("UNKNOWN") && !askClarification) {
            return new IntentRoutingDecision(
                    false, "UNKNOWN", confidence, reason, slots, candidates, false, "", List.of(),
                    topicSwitch, dialogAct, infoNovelty, currentTopic, previousTopic, contextPolicy
            );
        }
        return new IntentRoutingDecision(
                false, taskType, confidence, reason, slots, candidates, askClarification, clarificationQuestion, options,
                topicSwitch, dialogAct, infoNovelty, currentTopic, previousTopic, contextPolicy
        );
    }

    private boolean shouldClarify(List<IntentCandidate> candidates, double confidence, Map<String, Object> slots, String taskType) {
        if (confidence < properties.getConfidenceThreshold()) {
            return true;
        }
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
        return false;
    }

    private List<IntentTreeNode> resolveCandidateLeaves(String domainHint) {
        List<IntentTreeNode> leaves;
        if (domainHint != null && !domainHint.isBlank()) {
            leaves = intentTreeService.loadLeafIntentsByDomain(domainHint);
        } else {
            leaves = intentTreeService.loadAllLeafIntents();
        }
        if (leaves == null || leaves.isEmpty()) {
            leaves = defaultLeafIntents();
        }
        return leaves.stream()
                .filter(node -> node != null && node.intentId() != null && !node.intentId().isBlank())
                .toList();
    }

    private String normalizeContextPolicy(String rawPolicy, String dialogAct, boolean topicSwitch) {
        String normalized = rawPolicy == null ? "" : rawPolicy.trim().toUpperCase();
        if (normalized.equals("CONTINUE")
                || normalized.equals("SWITCH")
                || normalized.equals("RETURN")
                || normalized.equals("SUMMARY")
                || normalized.equals("SAFE_MIN")) {
            return normalized;
        }
        String normalizedDialogAct = dialogAct == null ? "" : dialogAct.trim().toUpperCase();
        if ("NEW_QUESTION".equals(normalizedDialogAct) || "COMPARISON".equals(normalizedDialogAct)) {
            return "SWITCH";
        }
        if ("SUMMARY".equals(normalizedDialogAct)) {
            return "SUMMARY";
        }
        if ("RETURN".equals(normalizedDialogAct)) {
            return "RETURN";
        }
        if (topicSwitch) {
            return "SWITCH";
        }
        return "CONTINUE";
    }

    private IntentRoutingDecision routeFailureDecision(String reason) {
        if (properties.isFallbackToLegacyTaskRouter()) {
            return IntentRoutingDecision.fallback();
        }
        String normalizedReason = reason == null ? "route_failed" : reason;
        return new IntentRoutingDecision(
                false, "UNKNOWN", 0D, "unified_classify_failed:" + normalizedReason,
                Map.of(), List.of(), true,
                "我没有完全理解你的意图，请补充你想进行：面试、刷题还是知识问答？",
                List.of(),
                false, "", 0.5D, "", "", "SAFE_MIN"
        );
    }

    private String inferDialogAct(String contextPolicy) {
        return switch (contextPolicy) {
            case "SWITCH" -> "NEW_QUESTION";
            case "RETURN" -> "RETURN";
            case "SUMMARY" -> "SUMMARY";
            default -> "FOLLOW_UP";
        };
    }

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

    private List<IntentTreeNode> defaultLeafIntents() {
        return List.of(
                new IntentTreeNode("INTERVIEW.START.GENERAL", "interview/start/general",
                        "开启模拟面试", "开始一场模拟面试", "INTERVIEW_START",
                        List.of("开始一场 Java 面试", "模拟面试"), List.of("topic", "skipIntro")),
                new IntentTreeNode("INTERVIEW.REPORT.GENERAL", "interview/report/general",
                        "生成面试报告", "生成面试复盘报告", "INTERVIEW_REPORT",
                        List.of("生成报告", "面试总结"), List.of("sessionId")),
                new IntentTreeNode("CODING.PRACTICE.GENERAL", "coding/practice/general",
                        "刷题练习", "编程刷题训练", "CODING_PRACTICE",
                        List.of("刷题", "来道题", "做一道算法题"), List.of("topic", "questionType", "difficulty", "count")),
                new IntentTreeNode("KNOWLEDGE.QA.GENERAL", "knowledge/qa/general",
                        "知识问答", "技术知识查询与概念解释", "KNOWLEDGE_QA",
                        List.of("什么是Redis持久化", "HashMap原理"), List.of("topic")),
                new IntentTreeNode("PROFILE.TRAINING.QUERY", "profile/training/query",
                        "查询学习计划", "学习画像与建议查询", "PROFILE_TRAINING_PLAN_QUERY",
                        List.of("查询学习计划", "我的薄弱点"), List.of("mode")),
                new IntentTreeNode("UNKNOWN", "unknown", "未知意图", "无法判定意图", "UNKNOWN",
                        List.of("你好", "今天天气不错"), List.of())
        );
    }

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
                    if (c > 0) {
                        slots.put("count", c);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (slotsNode.has("skipIntro") && !slotsNode.get("skipIntro").isNull()) {
            slots.put("skipIntro", slotsNode.get("skipIntro").asBoolean());
        }
        putSlot(slots, "mode", readText(slotsNode, "mode"));
        return slots;
    }

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

    private void putSlot(Map<String, Object> slots, String key, String value) {
        if (!value.isBlank()) {
            slots.put(key, value);
        }
    }

    private String readText(JsonNode node, String field) {
        if (node == null || field == null || !node.has(field) || node.get(field).isNull()) {
            return "";
        }
        return node.get(field).asText("").trim();
    }

    private double readScore(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return 0D;
        }
        return Math.max(0D, Math.min(1D, node.get(field).asDouble(0D)));
    }

    private boolean hasNonNullField(JsonNode node, String field) {
        return node != null && field != null && node.has(field) && !node.get(field).isNull();
    }

    private boolean readBoolean(JsonNode node, String field) {
        if (node == null || field == null || !node.has(field) || node.get(field).isNull()) {
            return false;
        }
        JsonNode value = node.get(field);
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isNumber()) {
            return value.asInt() != 0;
        }
        String text = value.asText("").trim();
        return "true".equalsIgnoreCase(text) || "1".equals(text);
    }

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

    private String textOf(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String extractFallbackTopic(String query) {
        if (query == null || query.isBlank()) {
            return "未知话题";
        }
        String trimmed = query.trim();
        return trimmed.length() > 20 ? trimmed.substring(0, 20) : trimmed;
    }

    private String sanitizeHistoryForIntentRouting(String history) {
        if (history == null || history.isBlank()) {
            return "";
        }
        String sanitized = history;
        int profileIndex = sanitized.indexOf("【用户画像】");
        if (profileIndex >= 0) {
            int sessionIndex = sanitized.indexOf("【会话摘要】", profileIndex);
            int recentIndex = sanitized.indexOf("【近期对话】", profileIndex);
            int nextSectionIndex = -1;
            if (sessionIndex >= 0 && recentIndex >= 0) {
                nextSectionIndex = Math.min(sessionIndex, recentIndex);
            } else if (sessionIndex >= 0) {
                nextSectionIndex = sessionIndex;
            } else if (recentIndex >= 0) {
                nextSectionIndex = recentIndex;
            }
            sanitized = nextSectionIndex >= 0
                    ? sanitized.substring(nextSectionIndex)
                    : "";
        }
        sanitized = sanitized.replace("AI: 正在生成中...", "");
        sanitized = sanitized.replace("AI: 正在生成中…", "");
        sanitized = sanitized.replaceAll("(?m)^\\s*$\\R?", "");
        return sanitized.trim();
    }

    private List<Map<String, Object>> toTemplateLeafIntents(List<IntentTreeNode> leafIntents) {
        if (leafIntents == null || leafIntents.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (IntentTreeNode leafIntent : leafIntents) {
            if (leafIntent == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("intentId", leafIntent.intentId());
            item.put("path", leafIntent.path());
            item.put("name", leafIntent.name());
            item.put("description", leafIntent.description());
            item.put("taskType", leafIntent.taskType());
            item.put("examples", leafIntent.examples() == null ? List.of() : leafIntent.examples());
            item.put("slotHints", leafIntent.slotHints() == null ? List.of() : leafIntent.slotHints());
            items.add(item);
        }
        return items;
    }

    private String truncateForLog(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.length() <= LOG_TEXT_LIMIT) {
            return trimmed;
        }
        return trimmed.substring(0, LOG_TEXT_LIMIT) + "...(truncated)";
    }
}
