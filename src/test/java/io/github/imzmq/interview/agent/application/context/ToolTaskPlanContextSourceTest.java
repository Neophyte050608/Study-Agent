package io.github.imzmq.interview.agent.application.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolTaskPlanContextSourceTest {

    @Test
    void fetchRendersGoalRequestAndPlan() {
        ToolTaskPlanContextSource source = new ToolTaskPlanContextSource();

        List<ContextItem> items = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.TOOL_TASK,
                "清理死代码",
                Map.of(
                        ToolTaskContextAttributes.TASK_GOAL, "清理死代码",
                        ToolTaskContextAttributes.USER_REQUEST, "先找不用的模块",
                        ToolTaskContextAttributes.TASK_PLAN, List.of("扫描引用", "移除无用类")
                )
        ));

        assertEquals(1, items.size());
        String text = items.get(0).text();
        assertTrue(text.contains("任务目标：清理死代码"));
        assertTrue(text.contains("用户请求：先找不用的模块"));
        assertTrue(text.contains("计划步骤：扫描引用、移除无用类"));
    }

    @Test
    void fetchFallsBackToQueryWhenGoalBlank() {
        ToolTaskPlanContextSource source = new ToolTaskPlanContextSource();

        List<ContextItem> items = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.TOOL_TASK,
                "整理模块结构",
                Map.of(ToolTaskContextAttributes.TASK_PLAN, "检查包结构")
        ));

        assertEquals(1, items.size());
        assertTrue(items.get(0).text().contains("任务目标：整理模块结构"));
    }

    private AgentContextSlot slot() {
        return new AgentContextSlot(AgentContextSlotKind.TASK_PLAN, false, AgentContextSlotFilter.none());
    }
}
