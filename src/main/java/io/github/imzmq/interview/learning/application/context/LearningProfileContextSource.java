package io.github.imzmq.interview.learning.application.context;

import io.github.imzmq.interview.agent.application.contract.context.AgentContextQuery;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSlot;
import io.github.imzmq.interview.agent.application.contract.context.AgentContextSlotKind;
import io.github.imzmq.interview.agent.application.contract.context.ContextItem;
import io.github.imzmq.interview.agent.application.contract.extension.AgentContextSource;

import io.github.imzmq.interview.learning.application.LearningProfileAgent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class LearningProfileContextSource implements AgentContextSource {

    private final LearningProfileAgent learningProfileAgent;

    public LearningProfileContextSource(LearningProfileAgent learningProfileAgent) {
        this.learningProfileAgent = learningProfileAgent;
    }

    @Override
    public String id() {
        return "learning-profile";
    }

    @Override
    public boolean supports(AgentContextSlotKind kind) {
        return kind == AgentContextSlotKind.PROFILE;
    }

    @Override
    public List<ContextItem> fetch(AgentContextSlot slot, AgentContextQuery query) {
        String userId = text(query, "userId");
        if (userId.isBlank() || learningProfileAgent == null) {
            return List.of();
        }
        String currentTopic = text(query, "currentTopic");
        String snapshot = learningProfileAgent.snapshotForPrompt(userId, currentTopic);
        if (snapshot == null || snapshot.isBlank() || "暂无历史学习画像。".equals(snapshot.trim())) {
            return List.of();
        }
        return List.of(new ContextItem(snapshot, 1.0, id(), Map.of("userId", userId)));
    }
    private String text(AgentContextQuery query, String key) {
        if (query == null || key == null || key.isBlank()) {
            return "";
        }
        Object value = query.attribute(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
