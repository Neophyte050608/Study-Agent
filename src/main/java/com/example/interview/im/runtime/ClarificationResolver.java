package com.example.interview.im.runtime;

import com.example.interview.agent.task.TaskResponse;
import com.example.interview.agent.task.TaskType;
import com.example.interview.config.IntentTreeProperties;
import com.example.interview.service.IntentTreeRoutingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ClarificationResolver {

    private final IntentTreeProperties intentTreeProperties;
    private final IntentTreeRoutingService intentTreeRoutingService;
    private final ObjectMapper objectMapper;

    public ClarificationResolver(IntentTreeProperties intentTreeProperties,
                                 IntentTreeRoutingService intentTreeRoutingService,
                                 ObjectMapper objectMapper) {
        this.intentTreeProperties = intentTreeProperties;
        this.intentTreeRoutingService = intentTreeRoutingService;
        this.objectMapper = objectMapper;
    }

    public TaskType resolveTaskType(String clarificationState, String userReply) {
        try {
            JsonNode root = objectMapper.readTree(clarificationState);
            JsonNode options = root.path("options");
            if (!options.isArray() || options.isEmpty()) {
                return null;
            }
            String normalizedReply = userReply == null ? "" : userReply.trim();
            if (normalizedReply.matches("^\\d+$")) {
                int selected = Integer.parseInt(normalizedReply);
                if (selected >= 1 && selected <= options.size()) {
                    String taskType = options.get(selected - 1).path("taskType").asText("");
                    return parseTaskType(taskType);
                }
            }
            for (JsonNode option : options) {
                String label = option.path("label").asText("");
                String taskType = option.path("taskType").asText("");
                if (!label.isBlank() && normalizedReply.contains(label)) {
                    return parseTaskType(taskType);
                }
                if (!taskType.isBlank() && normalizedReply.toUpperCase().contains(taskType.toUpperCase())) {
                    return parseTaskType(taskType);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public Map<String, Object> buildPayload(TaskType clarifiedTaskType, String userReply, String clarificationState) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("clarificationResolved", true);
        String originalQuery = readOriginalQuery(clarificationState);
        String normalizedReply = userReply == null ? "" : userReply.trim();
        if (!originalQuery.isBlank()) {
            payload.put("query", (originalQuery + " " + normalizedReply).trim());
        }
        String normalizedText = cleanupClarificationReply(normalizedReply);
        if (clarifiedTaskType == TaskType.CODING_PRACTICE) {
            if (normalizedText.contains("选择")) {
                payload.put("questionType", "CHOICE");
                payload.put("type", "选择题");
            } else if (normalizedText.contains("填空")) {
                payload.put("questionType", "FILL");
                payload.put("type", "填空题");
            } else if (normalizedText.contains("算法")) {
                payload.put("questionType", "ALGORITHM");
                payload.put("type", "算法题");
            }
            if (normalizedText.contains("简单") || normalizedText.contains("easy")) {
                payload.put("difficulty", "easy");
            } else if (normalizedText.contains("困难") || normalizedText.contains("hard")) {
                payload.put("difficulty", "hard");
            } else if (normalizedText.contains("中等") || normalizedText.contains("medium")) {
                payload.put("difficulty", "medium");
            }
            java.util.regex.Matcher countMatcher = java.util.regex.Pattern.compile("(\\d+)\\s*(道|题|个)").matcher(normalizedText);
            if (countMatcher.find()) {
                payload.put("count", Integer.parseInt(countMatcher.group(1)));
            }
            String topic = extractTopic(normalizedText);
            if (!topic.isBlank()) {
                payload.put("topic", topic);
            }
        } else if (clarifiedTaskType == TaskType.INTERVIEW_START) {
            if (normalizedText.contains("跳过自我介绍") || normalizedText.contains("直接出题") || normalizedText.contains("跳过介绍")) {
                payload.put("skipIntro", true);
            }
            String topic = extractTopic(normalizedText);
            if (!topic.isBlank()) {
                payload.put("topic", topic);
            }
        } else if (clarifiedTaskType == TaskType.PROFILE_TRAINING_PLAN_QUERY) {
            if (normalizedText.contains("学习")) {
                payload.put("mode", "learning");
            } else if (normalizedText.contains("面试")) {
                payload.put("mode", "interview");
            }
        }
        String mergedQuery = String.valueOf(payload.getOrDefault("query", normalizedReply));
        Map<String, Object> refinedSlots = intentTreeRoutingService == null ? Map.of()
                : intentTreeRoutingService.refineSlots(clarifiedTaskType.name(), mergedQuery, "");
        mergeMissingPayload(payload, refinedSlots);
        return payload;
    }

    public void captureState(String sessionId, TaskResponse response, com.example.interview.im.service.ImConversationStore conversationStore) {
        if (!(response.data() instanceof Map<?, ?> data)) {
            conversationStore.clearPendingClarification(sessionId);
            return;
        }
        Object clarification = data.containsKey("clarification") ? data.get("clarification") : "false";
        if (!Boolean.parseBoolean(String.valueOf(clarification))) {
            conversationStore.clearPendingClarification(sessionId);
            return;
        }
        Object options = data.get("clarificationOptions");
        if (!(options instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        try {
            Map<String, Object> state = new HashMap<>();
            state.put("options", list);
            Object originalQuery = data.containsKey("originalQuery") ? data.get("originalQuery") : "";
            state.put("originalQuery", String.valueOf(originalQuery));
            String stateJson = objectMapper.writeValueAsString(state);
            conversationStore.setPendingClarification(sessionId, stateJson, intentTreeProperties.getClarificationTtlMinutes());
        } catch (Exception ignored) {
        }
    }

    private void mergeMissingPayload(Map<String, Object> payload, Map<String, Object> refinedSlots) {
        if (refinedSlots == null || refinedSlots.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : refinedSlots.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || key.isBlank() || value == null || String.valueOf(value).isBlank()) {
                continue;
            }
            if (!payload.containsKey(key) || String.valueOf(payload.get(key)).isBlank()) {
                payload.put(key, value);
            }
        }
    }

    private String readOriginalQuery(String clarificationState) {
        try {
            JsonNode root = objectMapper.readTree(clarificationState);
            return root.path("originalQuery").asText("").trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String cleanupClarificationReply(String content) {
        if (content == null) {
            return "";
        }
        String normalized = content.trim();
        normalized = normalized.replaceFirst("^\\d+\\s*[)）.、:-]?\\s*", "");
        return normalized.trim();
    }

    private String extractTopic(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        List<String> topicKeywords = List.of("Java", "Spring Boot", "Spring", "Redis", "MySQL", "JVM", "并发", "算法", "网络", "操作系统");
        for (String keyword : topicKeywords) {
            if (text.toLowerCase().contains(keyword.toLowerCase())) {
                return keyword;
            }
        }
        return "";
    }

    private TaskType parseTaskType(String taskType) {
        if (taskType == null || taskType.isBlank() || "UNKNOWN".equalsIgnoreCase(taskType)) {
            return null;
        }
        try {
            return TaskType.valueOf(taskType.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
