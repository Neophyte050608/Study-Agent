package io.github.imzmq.interview.agent.application.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodingConstraintsContextSourceTest {

    @Test
    void fetchRendersCountQuestionTypeAndExcludedTopics() {
        CodingConstraintsContextSource source = new CodingConstraintsContextSource();

        List<ContextItem> items = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.CODING_PRACTICE,
                "数组",
                Map.of(
                        CodingPracticeContextAttributes.COUNT, 3,
                        CodingPracticeContextAttributes.QUESTION_TYPE, "选择题",
                        CodingPracticeContextAttributes.EXCLUDED_TOPICS, List.of("递归", "图")
                )
        ));

        assertEquals(1, items.size());
        String text = items.get(0).text();
        assertTrue(text.contains("题目数量：3"));
        assertTrue(text.contains("题型：选择题"));
        assertTrue(text.contains("避免重复主题：递归、图"));
    }

    @Test
    void fetchReturnsEmptyForNonCodingMode() {
        CodingConstraintsContextSource source = new CodingConstraintsContextSource();

        List<ContextItem> items = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.INTERVIEW,
                "数组",
                Map.of(CodingPracticeContextAttributes.COUNT, 3)
        ));

        assertTrue(items.isEmpty());
    }

    private AgentContextSlot slot() {
        return new AgentContextSlot(AgentContextSlotKind.CONSTRAINTS, false, AgentContextSlotFilter.none());
    }
}
