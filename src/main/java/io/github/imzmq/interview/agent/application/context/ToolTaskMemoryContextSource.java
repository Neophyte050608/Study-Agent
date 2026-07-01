package io.github.imzmq.interview.agent.application.context;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ToolTaskMemoryContextSource implements AgentContextSource {

    @Override
    public String id() {
        return "tool-task-memory";
    }

    @Override
    public boolean supports(AgentContextSlotKind kind) {
        return kind == AgentContextSlotKind.TASK_MEMORY;
    }

    @Override
    public List<ContextItem> fetch(AgentContextSlot slot, AgentContextQuery query) {
        if (query == null || query.mode() != AgentContextMode.TOOL_TASK) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        List<String> completedSteps = ToolTaskContextAttributes.stringList(query, ToolTaskContextAttributes.COMPLETED_STEPS);
        List<String> observations = ToolTaskContextAttributes.stringList(query, ToolTaskContextAttributes.OBSERVATIONS);
        if (!completedSteps.isEmpty()) {
            parts.add("已完成步骤：" + String.join("、", completedSteps));
        }
        if (!observations.isEmpty()) {
            parts.add("执行观察：" + String.join("、", observations));
        }
        if (parts.isEmpty()) {
            return List.of();
        }
        return List.of(new ContextItem(String.join("，", parts) + "。", 1.0, id(), Map.of()));
    }
}
