package io.github.imzmq.interview.agent.runtime;

import io.github.imzmq.interview.agent.core.Agent;
import io.github.imzmq.interview.mcp.application.McpGatewayService;
import io.github.imzmq.interview.knowledge.application.RAGService;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class NoteMakingAgent implements Agent<Map<String, Object>, Map<String, Object>> {
    private final RAGService ragService;
    private final McpGatewayService mcpGatewayService;
    private final Path notesDir;

    public NoteMakingAgent(
            RAGService ragService,
            McpGatewayService mcpGatewayService,
            @Value("${app.learning.notes.dir:learning-notes}") String notesDir
    ) {
        this.ragService = ragService;
        this.mcpGatewayService = mcpGatewayService;
        this.notesDir = Paths.get(notesDir).toAbsolutePath().normalize();
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input) {
        String action = text(input, "action").toLowerCase();
        if (action.isBlank() || "plan".equals(action)) {
            return createLearningPlan(input);
        }
        return Map.of(
                "agent", "NoteMakingAgent",
                "status", "bad_request",
                "message", "不支持的 action: " + action
        );
    }

    private Map<String, Object> createLearningPlan(Map<String, Object> input) {
        String topic = text(input, "topic");
        String weakPoint = text(input, "weakPoint");
        String recentPerformance = text(input, "recentPerformance");
        String userId = text(input, "userId");
        boolean useMcp = bool(input, "useMcp");
        String normalizedTopic = topic.isBlank() ? "后端基础" : topic;
        String plan = ragService.generateLearningPlan(normalizedTopic, weakPoint, recentPerformance);
        if (useMcp) {
            Map<String, Object> invokeResult = mcpGatewayService.invoke(
                    userId.isBlank() ? "anonymous" : userId,
                    "obsidian.write",
                    Map.of(
                            "topic", normalizedTopic,
                            "weakPoint", weakPoint,
                            "content", plan
                    ),
                    Map.of("source", "NoteMakingAgent")
            );
            Object result = invokeResult.get("result");
            String notePath = "";
            if (result instanceof Map<?, ?> map && map.get("notePath") != null) {
                notePath = String.valueOf(map.get("notePath"));
            }
            if (!notePath.isBlank()) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("agent", "NoteMakingAgent");
                data.put("status", "generated_via_mcp");
                data.put("topic", normalizedTopic);
                data.put("weakPoint", weakPoint);
                data.put("notePath", notePath);
                data.put("plan", plan);
                data.put("mcp", invokeResult);
                return data;
            }
        }
        String notePath;
        try {
            notePath = writeNote(normalizedTopic, weakPoint, plan);
        } catch (IOException e) {
            return Map.of(
                    "agent", "NoteMakingAgent",
                    "status", "io_error",
                    "message", "学习计划生成成功，但写入 Markdown 失败: " + e.getMessage(),
                    "plan", plan
            );
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agent", "NoteMakingAgent");
        data.put("status", "generated");
        data.put("topic", normalizedTopic);
        data.put("weakPoint", weakPoint);
        data.put("notePath", notePath);
        data.put("plan", plan);
        return data;
    }

    private String writeNote(String topic, String weakPoint, String plan) throws IOException {
        Files.createDirectories(notesDir);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String slug = topic.replaceAll("[^\\p{IsHan}\\w-]+", "-");
        if (slug.isBlank()) {
            slug = "learning-plan";
        }
        Path file = notesDir.resolve(timestamp + "-" + slug + ".md");
        String content = "# 学习计划 - " + topic + "\n\n" +
                "- 主题: " + topic + "\n" +
                "- 薄弱点: " + (weakPoint == null || weakPoint.isBlank() ? "待补充" : weakPoint) + "\n\n" +
                "## 7天计划\n\n" +
                plan + "\n";
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file.toString();
    }

    private String text(Map<String, Object> input, String key) {
        if (input == null) {
            return "";
        }
        Object value = input.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private boolean bool(Map<String, Object> input, String key) {
        String value = text(input, key);
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }
}



