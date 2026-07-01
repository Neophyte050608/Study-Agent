package io.github.imzmq.interview.agent.application.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolTaskConstraintsContextSourceTest {

    @Test
    void fetchRendersSafetyRulesAndConfirmationPolicy() {
        ToolTaskConstraintsContextSource source = new ToolTaskConstraintsContextSource();

        List<ContextItem> items = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.TOOL_TASK,
                "整理项目",
                Map.of(
                        ToolTaskContextAttributes.SAFETY_RULES, List.of("禁止删除用户文件", "禁止提交密钥"),
                        ToolTaskContextAttributes.CONFIRMATION_POLICY, "删除文件前必须确认"
                )
        ));

        assertEquals(1, items.size());
        String text = items.get(0).text();
        assertTrue(text.contains("安全规则：禁止删除用户文件、禁止提交密钥"));
        assertTrue(text.contains("确认策略：删除文件前必须确认"));
    }

    @Test
    void fetchReturnsEmptyForNonToolTaskMode() {
        ToolTaskConstraintsContextSource source = new ToolTaskConstraintsContextSource();

        List<ContextItem> items = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.KNOWLEDGE_QA,
                "query",
                Map.of(ToolTaskContextAttributes.CONFIRMATION_POLICY, "确认")
        ));

        assertTrue(items.isEmpty());
    }

    private AgentContextSlot slot() {
        return new AgentContextSlot(AgentContextSlotKind.CONSTRAINTS, false, AgentContextSlotFilter.none());
    }
}
