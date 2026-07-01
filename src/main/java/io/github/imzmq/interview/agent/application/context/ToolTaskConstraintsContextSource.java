package io.github.imzmq.interview.agent.application.context;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ToolTaskConstraintsContextSource implements AgentContextSource {

    @Override
    public String id() {
        return "tool-task-constraints";
    }

    @Override
    public boolean supports(AgentContextSlotKind kind) {
        return kind == AgentContextSlotKind.CONSTRAINTS;
    }

    @Override
    public List<ContextItem> fetch(AgentContextSlot slot, AgentContextQuery query) {
        if (query == null || query.mode() != AgentContextMode.TOOL_TASK) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        List<String> safetyRules = ToolTaskContextAttributes.stringList(query, ToolTaskContextAttributes.SAFETY_RULES);
        String confirmationPolicy = ToolTaskContextAttributes.text(query, ToolTaskContextAttributes.CONFIRMATION_POLICY);
        if (!safetyRules.isEmpty()) {
            parts.add("安全规则：" + String.join("、", safetyRules));
        }
        if (!confirmationPolicy.isBlank()) {
            parts.add("确认策略：" + confirmationPolicy);
        }
        if (parts.isEmpty()) {
            return List.of();
        }
        return List.of(new ContextItem(String.join("，", parts) + "。", 1.0, id(), Map.of()));
    }
}
