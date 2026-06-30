package io.github.imzmq.interview.agent.application.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterviewProfileContextSourceTest {

    @Test
    void fetchReturnsEmptyWhenProfileSnapshotBlankOrDefault() {
        InterviewProfileContextSource source = new InterviewProfileContextSource();

        List<ContextItem> blankItems = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.INTERVIEW,
                "query",
                Map.of(InterviewContextAttributes.PROFILE_SNAPSHOT, " ")
        ));
        List<ContextItem> defaultItems = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.INTERVIEW,
                "query",
                Map.of(InterviewContextAttributes.PROFILE_SNAPSHOT, "暂无历史学习画像。")
        ));

        assertTrue(blankItems.isEmpty());
        assertTrue(defaultItems.isEmpty());
    }

    @Test
    void fetchReturnsProfileSnapshotItem() {
        InterviewProfileContextSource source = new InterviewProfileContextSource();

        List<ContextItem> items = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.INTERVIEW,
                "query",
                Map.of(InterviewContextAttributes.PROFILE_SNAPSHOT, "弱项：JVM；熟项：Spring")
        ));

        assertEquals(1, items.size());
        assertEquals("弱项：JVM；熟项：Spring", items.get(0).text());
        assertEquals("interview-profile", items.get(0).source());
    }

    private AgentContextSlot slot() {
        return new AgentContextSlot(AgentContextSlotKind.PROFILE, false, AgentContextSlotFilter.none());
    }
}
