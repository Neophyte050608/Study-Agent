package io.github.imzmq.interview.agent.application.context;

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
        String userId = KnowledgeQaContextAttributes.text(query, KnowledgeQaContextAttributes.USER_ID);
        if (userId.isBlank() || learningProfileAgent == null) {
            return List.of();
        }
        String currentTopic = KnowledgeQaContextAttributes.text(query, KnowledgeQaContextAttributes.CURRENT_TOPIC);
        String snapshot = learningProfileAgent.snapshotForPrompt(userId, currentTopic);
        if (snapshot == null || snapshot.isBlank() || "暂无历史学习画像。".equals(snapshot.trim())) {
            return List.of();
        }
        return List.of(new ContextItem(snapshot, 1.0, id(), Map.of("userId", userId)));
    }
}
