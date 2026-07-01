package io.github.imzmq.interview.agent.application.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingTaskPlanContextSourceTest {

    @Test
    void fetchRendersTopicDifficultyTypeAndCount() {
        CodingTaskPlanContextSource source = new CodingTaskPlanContextSource();

        List<ContextItem> items = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.CODING_PRACTICE,
                "数组",
                Map.of(
                        CodingPracticeContextAttributes.TOPIC, "数组与字符串",
                        CodingPracticeContextAttributes.DIFFICULTY, "medium",
                        CodingPracticeContextAttributes.QUESTION_TYPE, "算法题",
                        CodingPracticeContextAttributes.COUNT, 2
                )
        ));

        assertEquals(1, items.size());
        String text = items.get(0).text();
        assertTrue(text.contains("练习主题：数组与字符串"));
        assertTrue(text.contains("难度：medium"));
        assertTrue(text.contains("题型：算法题"));
        assertTrue(text.contains("数量：2"));
    }

    @Test
    void fetchFallsBackToQueryTextWhenTopicAttributeBlank() {
        CodingTaskPlanContextSource source = new CodingTaskPlanContextSource();

        List<ContextItem> items = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.CODING_PRACTICE,
                "链表",
                Map.of(CodingPracticeContextAttributes.DIFFICULTY, "easy")
        ));

        assertEquals(1, items.size());
        assertTrue(items.get(0).text().contains("练习主题：链表"));
    }

    private AgentContextSlot slot() {
        return new AgentContextSlot(AgentContextSlotKind.TASK_PLAN, false, AgentContextSlotFilter.none());
    }
}
