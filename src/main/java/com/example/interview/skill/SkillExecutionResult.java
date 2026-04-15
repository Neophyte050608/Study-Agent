package com.example.interview.skill;

import java.util.List;
import java.util.Map;

public record SkillExecutionResult(
        String skillId,
        SkillExecutionStatus status,
        Map<String, Object> output,
        int attempts,
        boolean fallbackUsed,
        String message,
        List<String> toolCalls
) {

    public static SkillExecutionResult success(String skillId, Map<String, Object> output, int attempts, List<String> toolCalls) {
        return new SkillExecutionResult(skillId, SkillExecutionStatus.SUCCESS, output == null ? Map.of() : output, attempts, false, "ok", toolCalls == null ? List.of() : toolCalls);
    }

    public static SkillExecutionResult fallback(String skillId, String message, int attempts) {
        return new SkillExecutionResult(skillId, SkillExecutionStatus.FALLBACK, Map.of(), attempts, true, message, List.of());
    }

    public static SkillExecutionResult failed(String skillId, String message, int attempts) {
        return new SkillExecutionResult(skillId, SkillExecutionStatus.FAILED, Map.of(), attempts, false, message, List.of());
    }

    public static SkillExecutionResult skipped(String skillId, String message) {
        return new SkillExecutionResult(skillId, SkillExecutionStatus.SKIPPED, Map.of(), 0, false, message, List.of());
    }

    public boolean succeeded() {
        return status == SkillExecutionStatus.SUCCESS;
    }

    public String textOutput(String key) {
        Object value = output == null ? null : output.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    public boolean boolOutput(String key) {
        Object value = output == null ? null : output.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = value == null ? "" : String.valueOf(value).trim();
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text);
    }

    public double doubleOutput(String key, double defaultValue) {
        Object value = output == null ? null : output.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? defaultValue : Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }
}
