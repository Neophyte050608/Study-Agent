package io.github.imzmq.interview.agent.application.context;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ToolTaskPlanContextSource implements AgentContextSource {

    @Override
    public String id() {
        return "tool-task-plan";
    }

    @Override
    public boolean supports(AgentContextSlotKind kind) {
        return kind == AgentContextSlotKind.TASK_PLAN;
    }

    @Override
    public List<ContextItem> fetch(AgentContextSlot slot, AgentContextQuery query) {
        if (query == null || query.mode() != AgentContextMode.TOOL_TASK) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        String taskGoal = ToolTaskContextAttributes.text(query, ToolTaskContextAttributes.TASK_GOAL);
        if (taskGoal.isBlank()) {
            taskGoal = query.query();
        }
        String userRequest = ToolTaskContextAttributes.text(query, ToolTaskContextAttributes.USER_REQUEST);
        List<String> taskPlan = ToolTaskContextAttributes.stringList(query, ToolTaskContextAttributes.TASK_PLAN);
        if (taskGoal != null && !taskGoal.isBlank()) {
            parts.add("任务目标：" + taskGoal.trim());
        }
        if (!userRequest.isBlank()) {
            parts.add("用户请求：" + userRequest);
        }
        if (!taskPlan.isEmpty()) {
            parts.add("计划步骤：" + String.join("、", taskPlan));
        }
        if (parts.isEmpty()) {
            return List.of();
        }
        return List.of(new ContextItem(String.join("，", parts) + "。", 1.0, id(), Map.of()));
    }
}
