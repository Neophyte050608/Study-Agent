package io.github.imzmq.interview.skill.builtin;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import io.github.imzmq.interview.skill.core.ExecutableSkill;
import io.github.imzmq.interview.skill.core.SkillDefinition;
import io.github.imzmq.interview.skill.core.SkillExecutionContext;
import io.github.imzmq.interview.skill.core.SkillExecutionMode;
import io.github.imzmq.interview.skill.core.SkillExecutionResult;
import io.github.imzmq.interview.skill.policy.SkillFailureFallbackMode;
import io.github.imzmq.interview.skill.policy.SkillFailurePolicy;

@Component
public class PersonalizedLearningPlannerSkill implements ExecutableSkill {

    private static final SkillDefinition DEFINITION = new SkillDefinition(
            "personalized-learning-planner",
            "根据薄弱点、近期表现与主题生成务实的 7 天学习计划策略。",
            SkillExecutionMode.WORKFLOW,
            List.of(),
            new SkillFailurePolicy(1, 500L, 0L, 5, 60000L, SkillFailureFallbackMode.SKIP_SKILL)
    );

    @Override
    public SkillDefinition definition() {
        return DEFINITION;
    }

    @Override
    public SkillExecutionResult execute(SkillExecutionContext context) {
        String topic = context == null ? "" : context.text("topic");
        String weakPoint = context == null ? "" : context.text("weakPoint");
        String recentPerformance = context == null ? "" : context.text("recentPerformance");
        String planningSummary = buildPlanningSummary(topic, weakPoint, recentPerformance);
        return SkillExecutionResult.success(
                DEFINITION.id(),
                Map.of(
                        "planningSummary", planningSummary,
                        "topic", topic == null ? "" : topic
                ),
                1,
                List.of()
        );
    }

    private String buildPlanningSummary(String topic, String weakPoint, String recentPerformance) {
        String normalizedTopic = topic == null || topic.isBlank() ? "后端基础" : topic.trim();
        String focus = weakPoint == null || weakPoint.isBlank()
                ? normalizedTopic + " 的核心概念、原理和高频追问"
                : weakPoint.trim();
        String intensity = recentPerformance != null && (
                recentPerformance.contains("低分")
                        || recentPerformance.contains("薄弱")
                        || recentPerformance.contains("错误")
                        || recentPerformance.contains("不会")
        )
                ? "前 3 天集中补基础，后 4 天逐步加练习和复盘。"
                : "按循序渐进节奏安排，每天兼顾概念、练习和复盘。";
        return "【Learning Plan Strategy】\n- 主题: " + normalizedTopic + "\n- 聚焦短板: " + focus + "\n- 节奏: " + intensity + "\n- 要求: 每天都包含学习目标、练习动作、复盘动作，负载控制在 1-2 小时。";
    }
}

