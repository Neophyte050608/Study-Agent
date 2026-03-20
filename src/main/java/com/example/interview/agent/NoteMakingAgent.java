package com.example.interview.agent;

import com.example.interview.service.McpGatewayService;
import com.example.interview.service.RAGService;
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

/**
 * 笔记生成智能体（NoteMakingAgent）。
 * 
 * 核心职责：负责将用户的面试表现、薄弱点转化成可执行的学习计划笔记。
 * 支持两种保存方式：
 * 1. 本地文件系统：直接在项目的笔记目录下生成 Markdown 文件。
 * 2. MCP 联动：通过 MCP 协议调用外部工具（如 Obsidian）进行云端或本地知识库同步。
 */
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

    /**
     * 执行笔记生成相关的操作。
     * 目前主要支持 action 为 "plan" (生成学习计划)。
     * 
     * @param input 包含 topic, weakPoint, recentPerformance 等参数的 Map
     * @return 包含生成状态和笔记路径的响应
     */
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

    /**
     * 创建学习计划笔记。
     */
    private Map<String, Object> createLearningPlan(Map<String, Object> input) {
        String topic = text(input, "topic");
        String weakPoint = text(input, "weakPoint");
        String recentPerformance = text(input, "recentPerformance");
        String userId = text(input, "userId");
        boolean useMcp = bool(input, "useMcp");
        
        String normalizedTopic = topic.isBlank() ? "后端基础" : topic;
        
        // 1. 调用 RAG 生成结构化的学习计划内容
        String plan = ragService.generateLearningPlan(normalizedTopic, weakPoint, recentPerformance);
        
        // 2. 如果开启了 MCP，则尝试通过 MCP 工具写入（例如写入 Obsidian）
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
        
        // 3. 兜底方案：直接在本地文件系统写入 Markdown
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

    /**
     * 将生成的计划写入本地 Markdown 文件。
     */
    private String writeNote(String topic, String weakPoint, String plan) throws IOException {
        Files.createDirectories(notesDir);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        // 归一化文件名，处理非中英文字符
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
