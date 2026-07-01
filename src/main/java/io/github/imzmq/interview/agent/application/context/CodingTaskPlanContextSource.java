package io.github.imzmq.interview.agent.application.context;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class CodingTaskPlanContextSource implements AgentContextSource {

    @Override
    public String id() {
        return "coding-task-plan";
    }

    @Override
    public boolean supports(AgentContextSlotKind kind) {
        return kind == AgentContextSlotKind.TASK_PLAN;
    }

    @Override
    public List<ContextItem> fetch(AgentContextSlot slot, AgentContextQuery query) {
        if (query == null || query.mode() != AgentContextMode.CODING_PRACTICE) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        String topic = CodingPracticeContextAttributes.text(query, CodingPracticeContextAttributes.TOPIC);
        if (topic.isBlank()) {
            topic = query.query();
        }
        String difficulty = CodingPracticeContextAttributes.text(query, CodingPracticeContextAttributes.DIFFICULTY);
        String questionType = CodingPracticeContextAttributes.text(query, CodingPracticeContextAttributes.QUESTION_TYPE);
        int count = CodingPracticeContextAttributes.integer(query, CodingPracticeContextAttributes.COUNT, 0);
        if (topic != null && !topic.isBlank()) {
            parts.add("练习主题：" + topic.trim());
        }
        if (!difficulty.isBlank()) {
            parts.add("难度：" + difficulty);
        }
        if (!questionType.isBlank()) {
            parts.add("题型：" + questionType);
        }
        if (count > 0) {
            parts.add("数量：" + count);
        }
        if (parts.isEmpty()) {
            return List.of();
        }
        return List.of(new ContextItem(String.join("，", parts) + "。", 1.0, id(), Map.of()));
    }
}
