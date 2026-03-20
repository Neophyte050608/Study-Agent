package com.example.interview.tool;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * MCP 桩 (Stub) 实现。
 * 
 * 职责：
 * 1. 本地开发支持：在没有真实 MCP 服务器时提供模拟的能力发现和调用。
 * 2. 兜底降级：当 SSE/Stdio 网关不可用时，McpGatewayService 会回退到此桩实现，保证流程不中断。
 */
@Component
public class StubMcpCapabilityGateway implements McpCapabilityGateway {
    @Override
    public List<String> listCapabilities() {
        return List.of("obsidian.read", "obsidian.write", "web.search", "code.execute");
    }

    @Override
    public Object invokeCapability(String name, Map<String, Object> params) {
        String capability = name == null ? "" : name.trim();
        if ("obsidian.write".equalsIgnoreCase(capability)) {
            String topic = text(params, "topic");
            String slug = (topic.isBlank() ? "learning-note" : topic).replaceAll("[^\\p{IsHan}\\w-]+", "-");
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            return Map.of(
                    "capability", capability,
                    "status", "ok",
                    "notePath", "mcp://obsidian/" + timestamp + "-" + slug + ".md",
                    "contentLength", text(params, "content").length()
            );
        }
        if ("code.execute".equalsIgnoreCase(capability)) {
            return Map.of(
                    "capability", capability,
                    "status", "ok",
                    "exitCode", 0,
                    "stdout", "stub execution finished"
            );
        }
        return Map.of(
                "capability", capability,
                "status", "stub",
                "params", params == null ? Map.of() : params
        );
    }

    private String text(Map<String, Object> params, String key) {
        if (params == null) {
            return "";
        }
        Object value = params.get(key);
        return value == null ? "" : value.toString().trim();
    }
}
