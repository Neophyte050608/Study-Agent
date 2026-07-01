package io.github.imzmq.interview.agent.application.context;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ToolStateContextSource implements AgentContextSource {

    @Override
    public String id() {
        return "tool-state";
    }

    @Override
    public boolean supports(AgentContextSlotKind kind) {
        return kind == AgentContextSlotKind.TOOL_STATE;
    }

    @Override
    public List<ContextItem> fetch(AgentContextSlot slot, AgentContextQuery query) {
        if (query == null || query.mode() != AgentContextMode.TOOL_TASK) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        List<String> availableTools = ToolTaskContextAttributes.stringList(query, ToolTaskContextAttributes.AVAILABLE_TOOLS);
        List<String> disabledTools = ToolTaskContextAttributes.stringList(query, ToolTaskContextAttributes.DISABLED_TOOLS);
        String lastToolResult = ToolTaskContextAttributes.text(query, ToolTaskContextAttributes.LAST_TOOL_RESULT);
        if (!availableTools.isEmpty()) {
            parts.add("可用工具：" + String.join("、", availableTools));
        }
        if (!disabledTools.isEmpty()) {
            parts.add("禁用工具：" + String.join("、", disabledTools));
        }
        if (!lastToolResult.isBlank()) {
            parts.add("最近工具结果：" + lastToolResult);
        }
        if (parts.isEmpty()) {
            return List.of();
        }
        return List.of(new ContextItem(String.join("，", parts) + "。", 1.0, id(), Map.of()));
    }
}
