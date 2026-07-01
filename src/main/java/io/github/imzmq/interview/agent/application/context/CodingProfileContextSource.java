package io.github.imzmq.interview.agent.application.context;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class CodingProfileContextSource implements AgentContextSource {

    @Override
    public String id() {
        return "coding-profile";
    }

    @Override
    public boolean supports(AgentContextSlotKind kind) {
        return kind == AgentContextSlotKind.PROFILE;
    }

    @Override
    public List<ContextItem> fetch(AgentContextSlot slot, AgentContextQuery query) {
        if (query == null || query.mode() != AgentContextMode.CODING_PRACTICE) {
            return List.of();
        }
        String snapshot = CodingPracticeContextAttributes.text(query, CodingPracticeContextAttributes.PROFILE_SNAPSHOT);
        if (snapshot.isBlank() || "暂无历史学习画像。".equals(snapshot)) {
            return List.of();
        }
        return List.of(new ContextItem(snapshot, 1.0, id(), Map.of()));
    }
}
