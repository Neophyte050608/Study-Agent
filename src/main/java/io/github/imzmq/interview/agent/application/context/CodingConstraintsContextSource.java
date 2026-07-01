package io.github.imzmq.interview.agent.application.context;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class CodingConstraintsContextSource implements AgentContextSource {

    @Override
    public String id() {
        return "coding-constraints";
    }

    @Override
    public boolean supports(AgentContextSlotKind kind) {
        return kind == AgentContextSlotKind.CONSTRAINTS;
    }

    @Override
    public List<ContextItem> fetch(AgentContextSlot slot, AgentContextQuery query) {
        if (query == null || query.mode() != AgentContextMode.CODING_PRACTICE) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        int count = CodingPracticeContextAttributes.integer(query, CodingPracticeContextAttributes.COUNT, 0);
        String questionType = CodingPracticeContextAttributes.text(query, CodingPracticeContextAttributes.QUESTION_TYPE);
        List<String> excludedTopics = CodingPracticeContextAttributes.stringList(query, CodingPracticeContextAttributes.EXCLUDED_TOPICS);
        if (count > 0) {
            parts.add("题目数量：" + count);
        }
        if (!questionType.isBlank()) {
            parts.add("题型：" + questionType);
        }
        if (!excludedTopics.isEmpty()) {
            parts.add("避免重复主题：" + String.join("、", excludedTopics));
        }
        if (parts.isEmpty()) {
            return List.of();
        }
        return List.of(new ContextItem(String.join("，", parts) + "。", 1.0, id(), Map.of()));
    }
}
