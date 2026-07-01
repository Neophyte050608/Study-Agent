package io.github.imzmq.interview.agent.application.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolTaskContextAttributesTest {

    @Test
    void textReturnsTrimmedValue() {
        AgentContextQuery query = AgentContextQuery.create(
                AgentContextMode.TOOL_TASK,
                "整理项目",
                Map.of(ToolTaskContextAttributes.TASK_GOAL, "  清理死代码  ")
        );

        assertEquals("清理死代码", ToolTaskContextAttributes.text(query, ToolTaskContextAttributes.TASK_GOAL));
    }

    @Test
    void stringListReadsListAndSingleString() {
        AgentContextQuery listQuery = AgentContextQuery.create(
                AgentContextMode.TOOL_TASK,
                "整理项目",
                Map.of(ToolTaskContextAttributes.TASK_PLAN, List.of("检查引用", " 删除空项 ", ""))
        );
        AgentContextQuery stringQuery = AgentContextQuery.create(
                AgentContextMode.TOOL_TASK,
                "整理项目",
                Map.of(ToolTaskContextAttributes.SAFETY_RULES, " 不删除用户文件 ")
        );

        assertEquals(List.of("检查引用", "删除空项"), ToolTaskContextAttributes.stringList(listQuery, ToolTaskContextAttributes.TASK_PLAN));
        assertEquals(List.of("不删除用户文件"), ToolTaskContextAttributes.stringList(stringQuery, ToolTaskContextAttributes.SAFETY_RULES));
        assertTrue(ToolTaskContextAttributes.stringList(null, ToolTaskContextAttributes.TASK_PLAN).isEmpty());
    }
}
