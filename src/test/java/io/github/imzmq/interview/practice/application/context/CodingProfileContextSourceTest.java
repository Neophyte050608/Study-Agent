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

class CodingProfileContextSourceTest {

    @Test
    void fetchUsesProfileSnapshotAttribute() {
        CodingProfileContextSource source = new CodingProfileContextSource();

        List<ContextItem> items = source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.CODING_PRACTICE,
                "数组",
                Map.of(CodingPracticeContextAttributes.PROFILE_SNAPSHOT, "弱项：边界条件")
        ));

        assertEquals(1, items.size());
        assertEquals("弱项：边界条件", items.get(0).text());
        assertEquals("coding-profile", items.get(0).source());
    }

    @Test
    void fetchIgnoresBlankOrDefaultProfile() {
        CodingProfileContextSource source = new CodingProfileContextSource();

        assertTrue(source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.CODING_PRACTICE,
                "数组",
                Map.of(CodingPracticeContextAttributes.PROFILE_SNAPSHOT, "   ")
        )).isEmpty());
        assertTrue(source.fetch(slot(), AgentContextQuery.create(
                AgentContextMode.CODING_PRACTICE,
                "数组",
                Map.of(CodingPracticeContextAttributes.PROFILE_SNAPSHOT, "暂无历史学习画像。")
        )).isEmpty());
    }

    private AgentContextSlot slot() {
        return new AgentContextSlot(AgentContextSlotKind.PROFILE, false, AgentContextSlotFilter.none());
    }
}
