package io.github.imzmq.interview.practice.application.context;

import io.github.imzmq.interview.agent.application.contract.context.AgentContextMode;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextQuery;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSlot;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSlotKind;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSlotFilter;
import io.github.imzmq.interview.agent.application.contract.context.ContextItem;

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
