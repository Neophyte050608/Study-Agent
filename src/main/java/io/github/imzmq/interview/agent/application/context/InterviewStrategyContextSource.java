package io.github.imzmq.interview.agent.application.context;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class InterviewStrategyContextSource implements AgentContextSource {

    @Override
    public String id() {
        return "interview-strategy";
    }

    @Override
    public boolean supports(AgentContextSlotKind kind) {
        return kind == AgentContextSlotKind.TASK_PLAN;
    }

    @Override
    public List<ContextItem> fetch(AgentContextSlot slot, AgentContextQuery query) {
        if (query == null || query.mode() != AgentContextMode.INTERVIEW) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        String difficulty = InterviewContextAttributes.text(query, InterviewContextAttributes.DIFFICULTY_LEVEL);
        String followUp = InterviewContextAttributes.text(query, InterviewContextAttributes.FOLLOW_UP_STATE);
        double mastery = InterviewContextAttributes.number(query, InterviewContextAttributes.TOPIC_MASTERY, -1.0);
        String currentStage = InterviewContextAttributes.text(query, InterviewContextAttributes.CURRENT_STAGE);
        String nextStage = InterviewContextAttributes.text(query, InterviewContextAttributes.NEXT_STAGE);
        String strategy = InterviewContextAttributes.text(query, InterviewContextAttributes.STRATEGY_HINT);

        if (!difficulty.isBlank()) {
            parts.add("当前难度：" + difficulty);
        }
        if (!followUp.isBlank()) {
            parts.add("追问状态：" + followUp);
        }
        if (mastery >= 0.0) {
            parts.add("主题掌握度：" + String.format("%.1f", mastery));
        }
        if (!currentStage.isBlank()) {
            parts.add("当前阶段：" + currentStage);
        }
        if (!nextStage.isBlank()) {
            parts.add("下一阶段：" + nextStage);
        }
        if (!strategy.isBlank()) {
            parts.add("评估策略：" + strategy);
        }
        if (parts.isEmpty()) {
            return List.of();
        }
        return List.of(new ContextItem(String.join("，", parts) + "。", 1.0, id(), Map.of()));
    }
}
