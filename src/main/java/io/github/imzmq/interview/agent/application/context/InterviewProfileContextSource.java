package io.github.imzmq.interview.agent.application.context;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class InterviewProfileContextSource implements AgentContextSource {

    @Override
    public String id() {
        return "interview-profile";
    }

    @Override
    public boolean supports(AgentContextSlotKind kind) {
        return kind == AgentContextSlotKind.PROFILE;
    }

    @Override
    public List<ContextItem> fetch(AgentContextSlot slot, AgentContextQuery query) {
        if (query == null || query.mode() != AgentContextMode.INTERVIEW) {
            return List.of();
        }
        String profile = InterviewContextAttributes.text(query, InterviewContextAttributes.PROFILE_SNAPSHOT);
        if (profile.isBlank() || "暂无历史学习画像。".equals(profile)) {
            return List.of();
        }
        return List.of(new ContextItem(profile, 1.0, id(), Map.of("kind", "profileSnapshot")));
    }
}
