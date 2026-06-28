package io.github.imzmq.interview.tool.core;

import java.util.Map;

public record ToolExecutionResult(
        String toolId,
        ToolExecutionStatus status,
        Map<String, Object> output,
        String message,
        int attempts,
        boolean fallbackUsed,
        long latencyMs
) {

    public ToolExecutionResult {
        toolId = safeText(toolId);
        status = status == null ? ToolExecutionStatus.FAILED : status;
        output = output == null ? Map.of() : Map.copyOf(output);
        message = safeText(message);
        attempts = Math.max(0, attempts);
        latencyMs = Math.max(0L, latencyMs);
    }

    public static ToolExecutionResult success(String toolId, Map<String, Object> output, int attempts, long latencyMs) {
        return new ToolExecutionResult(toolId, ToolExecutionStatus.SUCCESS, output, "ok", attempts, false, latencyMs);
    }

    public static ToolExecutionResult failed(String toolId, String message) {
        return new ToolExecutionResult(toolId, ToolExecutionStatus.FAILED, Map.of(), message, 0, false, 0L);
    }

    public static ToolExecutionResult skipped(String toolId, String message) {
        return new ToolExecutionResult(toolId, ToolExecutionStatus.SKIPPED, Map.of(), message, 0, false, 0L);
    }

    public boolean succeeded() {
        return status == ToolExecutionStatus.SUCCESS;
    }

    private static String safeText(String value) {
        return value == null ? "" : value.trim();
    }
}
