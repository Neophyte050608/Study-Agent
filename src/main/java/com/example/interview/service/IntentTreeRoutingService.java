package com.example.interview.service;

import com.example.interview.config.IntentTreeProperties;
import com.example.interview.intent.IntentCandidate;
import com.example.interview.intent.IntentRoutingDecision;
import com.example.interview.intent.IntentTreeNode;
import com.example.interview.modelrouting.ModelRouteType;
import com.example.interview.modelrouting.RoutingChatService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class IntentTreeRoutingService {

    private final PromptManager promptManager;
    private final IntentTreeProperties properties;
    private final IntentTreeService intentTreeService;
    private final RoutingChatService routingChatService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IntentTreeRoutingService(
            PromptManager promptManager,
            IntentTreeProperties properties,
            IntentTreeService intentTreeService,
            RoutingChatService routingChatService
    ) {
        this.promptManager = promptManager;
        this.properties = properties;
        this.intentTreeService = intentTreeService;
        this.routingChatService = routingChatService;
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    public long clarificationTtlMinutes() {
        return properties.getClarificationTtlMinutes();
    }

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
            String prompt = promptManager.render("intent-tree-classifier", vars);
            String response = routingChatService.call(prompt, ModelRouteType.THINKING, "意图树分类");
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
            String prompt = promptManager.render("intent-slot-refine", vars);
            String response = routingChatService.call(prompt, ModelRouteType.THINKING, "意图槽位精炼");
            if (response == null || response.isBlank()) {
                return Map.of();
            }
            String clean = response.replace("```json", "").replace("```", "").trim();
            JsonNode root = objectMapper.readTree(clean);
            JsonNode slotsNode = root.has("slots") ? root.get("slots") : root;
            Map<String, Object> slots = readSlots(slotsNode);
            String questionType = textOf(slots.get("questionType"));
            if (!questionType.isBlank()) {
                String type = switch (questionType.toUpperCase()) {
                    case "CHOICE" -> "选择题";
                    case "FILL" -> "填空题";
                    default -> "算法题";
                };
                slots.put("type", type);
            }
            return slots;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private List<Map<String, String>> loadSlotRefineCases(String taskType) {
        String normalizedTaskType = taskType == null ? "" : taskType.trim().toUpperCase();
        List<Map<String, String>> configuredCases = new ArrayList<>();
        for (IntentTreeProperties.SlotRefineCase refineCase : properties.getSlotRefineCases()) {
            if (refineCase == null) {
                continue;
            }
            String caseTaskType = textOf(refineCase.getTaskType()).toUpperCase();
            if (!caseTaskType.isBlank() && !caseTaskType.equals(normalizedTaskType)) {
                continue;
            }
            String userQuery = textOf(refineCase.getUserQuery());
            String aiOutput = textOf(refineCase.getAiOutput());
            if (userQuery.isBlank() || aiOutput.isBlank()) {
                continue;
            }
            configuredCases.add(Map.of("user_query", userQuery, "ai_response", aiOutput));
        }
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
        if ("CODING_PRACTICE".equals(taskType) && textOf(slots.get("topic")).isBlank() && textOf(slots.get("questionType")).isBlank()) {
            return true;
        }
        return false;
    }

    private String buildClarificationQuestion(String query, String history, List<IntentCandidate> candidates) {
        try {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("query", query);
            vars.put("history", history == null ? "" : history);
            vars.put("candidates", candidates.stream().limit(Math.max(1, properties.getMaxCandidates())).toList());
            String prompt = promptManager.render("intent-clarification", vars);
            String response = routingChatService.call(prompt, ModelRouteType.GENERAL, "意图澄清");
            if (response != null && !response.isBlank()) {
                return response.trim();
            }
        } catch (Exception ignored) {
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

    private List<IntentTreeNode> loadLeafIntents() {
        List<IntentTreeNode> configured = intentTreeService.loadAllLeafIntents();
        if (!configured.isEmpty()) {
            return configured;
        }
        return defaultLeafIntents();
    }

    private List<IntentTreeNode> defaultLeafIntents() {
        return List.of(
                new IntentTreeNode("INTERVIEW.START.GENERAL", "interview/start/general", "开启模拟面试", "开始一场模拟面试", "INTERVIEW_START",
                        List.of("开始一场 Java 面试", "我们来一场 Spring Boot 模拟面试"), List.of("topic", "skipIntro")),
                new IntentTreeNode("INTERVIEW.REPORT.GENERAL", "interview/report/general", "生成面试报告", "生成已结束面试的复盘报告", "INTERVIEW_REPORT",
                        List.of("生成报告", "给我这次面试总结"), List.of("sessionId")),
                new IntentTreeNode("CODING.PRACTICE.QUESTION", "coding/practice/question", "刷题练习", "题目训练与刷题请求", "CODING_PRACTICE",
                        List.of("来一道算法题", "出一道 Java 选择题"), List.of("topic", "questionType", "difficulty", "count")),
                new IntentTreeNode("PROFILE.TRAINING.QUERY", "profile/training/query", "查询学习计划", "查询学习画像或学习建议", "PROFILE_TRAINING_PLAN_QUERY",
                        List.of("查询我的学习计划", "我最近薄弱点是什么"), List.of("mode")),
                new IntentTreeNode("UNKNOWN", "unknown", "未知意图", "无法判定具体业务意图", "UNKNOWN",
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
        if (slotsNode.has("count") && slotsNode.get("count").isInt()) {
            slots.put("count", slotsNode.get("count").asInt());
        }
        if (slotsNode.has("skipIntro") && !slotsNode.get("skipIntro").isNull()) {
            slots.put("skipIntro", slotsNode.get("skipIntro").asBoolean());
        }
        putSlot(slots, "mode", readText(slotsNode, "mode"));
        return slots;
    }

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
}
