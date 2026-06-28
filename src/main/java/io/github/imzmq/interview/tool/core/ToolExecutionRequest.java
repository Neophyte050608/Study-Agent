package io.github.imzmq.interview.tool.core;

import io.github.imzmq.interview.tool.policy.ToolRiskLevel;

import java.util.Map;

public record ToolExecutionRequest(
        String traceId,
        String operator,
        String toolId,
        Map<String, Object> input,
        String source,
        ToolRiskLevel riskLevel,
        boolean dryRun,
        Map<String, Object> metadata
) {

    public ToolExecutionRequest {
        traceId = safeText(traceId);
        operator = safeText(operator).isBlank() ? "anonymous" : safeText(operator);
        toolId = safeText(toolId);
        input = input == null ? Map.of() : Map.copyOf(input);
        source = safeText(source).isBlank() ? "agent" : safeText(source);
        riskLevel = riskLevel == null ? ToolRiskLevel.LOW : riskLevel;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static ToolExecutionRequest of(String toolId, String operator, Map<String, Object> input) {
        return new ToolExecutionRequest("", operator, toolId, input, "agent", ToolRiskLevel.LOW, false, Map.of());
    }

    private static String safeText(String value) {
        return value == null ? "" : value.trim();
    }
}
