package io.github.imzmq.interview.agent.application.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolTaskMemoryContextSourceTest {

    @Test
    void fetchRendersCompletedStepsAndObservations() {
        ToolTaskMemoryContextSource source = new ToolTaskMemoryContextSource();

        List<ContextItem> items = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.TOOL_TASK,
                "整理项目",
                Map.of(
                        ToolTaskContextAttributes.COMPLETED_STEPS, List.of("读取规范", "检查目录"),
                        ToolTaskContextAttributes.OBSERVATIONS, List.of("发现旧 service 包", "AGENTS.md 有本地注入")
                )
        ));

        assertEquals(1, items.size());
        String text = items.get(0).text();
        assertTrue(text.contains("已完成步骤：读取规范、检查目录"));
        assertTrue(text.contains("执行观察：发现旧 service 包、AGENTS.md 有本地注入"));
    }

    private AgentContextSlot slot() {
        return new AgentContextSlot(AgentContextSlotKind.TASK_MEMORY, false, AgentContextSlotFilter.none());
    }
}
