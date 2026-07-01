package io.github.imzmq.interview.agent.application.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolStateContextSourceTest {

    @Test
    void fetchRendersAvailableDisabledToolsAndLastResult() {
        ToolStateContextSource source = new ToolStateContextSource();

        List<ContextItem> items = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.TOOL_TASK,
                "整理项目",
                Map.of(
                        ToolTaskContextAttributes.AVAILABLE_TOOLS, List.of("read_file", "run_tests"),
                        ToolTaskContextAttributes.DISABLED_TOOLS, "delete_file",
                        ToolTaskContextAttributes.LAST_TOOL_RESULT, "测试通过 12 个"
                )
        ));

        assertEquals(1, items.size());
        String text = items.get(0).text();
        assertTrue(text.contains("可用工具：read_file、run_tests"));
        assertTrue(text.contains("禁用工具：delete_file"));
        assertTrue(text.contains("最近工具结果：测试通过 12 个"));
    }

    private AgentContextSlot slot() {
        return new AgentContextSlot(AgentContextSlotKind.TOOL_STATE, false, AgentContextSlotFilter.none());
    }
}
