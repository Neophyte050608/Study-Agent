package io.github.imzmq.interview.agent.application.context;

import java.util.List;

public final class ToolTaskContextAttributes {
    public static final String TASK_GOAL = "toolTask.taskGoal";
    public static final String USER_REQUEST = "toolTask.userRequest";
    public static final String TASK_PLAN = "toolTask.taskPlan";
    public static final String COMPLETED_STEPS = "toolTask.completedSteps";
    public static final String OBSERVATIONS = "toolTask.observations";
    public static final String AVAILABLE_TOOLS = "toolTask.availableTools";
    public static final String DISABLED_TOOLS = "toolTask.disabledTools";
    public static final String LAST_TOOL_RESULT = "toolTask.lastToolResult";
    public static final String CONFIRMATION_POLICY = "toolTask.confirmationPolicy";
    public static final String SAFETY_RULES = "toolTask.safetyRules";

    private ToolTaskContextAttributes() {
    }

    public static String text(AgentContextQuery query, String key) {
        Object value = query == null ? null : query.attribute(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    public static List<String> stringList(AgentContextQuery query, String key) {
        Object value = query == null ? null : query.attribute(key);
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(item -> item == null ? "" : String.valueOf(item).trim())
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text.trim());
        }
        return List.of();
    }
}
