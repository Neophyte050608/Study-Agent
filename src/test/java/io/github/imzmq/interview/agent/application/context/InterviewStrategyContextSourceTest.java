package io.github.imzmq.interview.agent.application.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterviewStrategyContextSourceTest {

    @Test
    void fetchRendersDifficultyFollowUpMasteryAndStrategy() {
        InterviewStrategyContextSource source = new InterviewStrategyContextSource();

        List<ContextItem> items = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.INTERVIEW,
                "query",
                Map.of(
                        InterviewContextAttributes.DIFFICULTY_LEVEL, "ADVANCED",
                        InterviewContextAttributes.FOLLOW_UP_STATE, "PROBE",
                        InterviewContextAttributes.TOPIC_MASTERY, 62.0,
                        InterviewContextAttributes.STRATEGY_HINT, "保持中等强度评估，兼顾理论与实践。"
                )
        ));

        assertEquals(1, items.size());
        String text = items.get(0).text();
        assertTrue(text.contains("当前难度：ADVANCED"));
        assertTrue(text.contains("追问状态：PROBE"));
        assertTrue(text.contains("主题掌握度：62.0"));
        assertTrue(text.contains("评估策略：保持中等强度评估"));
    }

    @Test
    void fetchReturnsEmptyForNonInterviewMode() {
        InterviewStrategyContextSource source = new InterviewStrategyContextSource();

        List<ContextItem> items = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.KNOWLEDGE_QA,
                "query",
                Map.of(InterviewContextAttributes.STRATEGY_HINT, "策略")
        ));

        assertTrue(items.isEmpty());
    }

    private AgentContextSlot slot() {
        return new AgentContextSlot(AgentContextSlotKind.TASK_PLAN, false, AgentContextSlotFilter.none());
    }
}
