package io.github.imzmq.interview.skill.builtin;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import io.github.imzmq.interview.skill.core.ExecutableSkill;
import io.github.imzmq.interview.skill.core.SkillDefinition;
import io.github.imzmq.interview.skill.core.SkillExecutionContext;
import io.github.imzmq.interview.skill.core.SkillExecutionMode;
import io.github.imzmq.interview.skill.core.SkillExecutionResult;
import io.github.imzmq.interview.skill.policy.SkillFailureFallbackMode;
import io.github.imzmq.interview.skill.policy.SkillFailurePolicy;

@Component
public class CodingInterviewCoachSkill implements ExecutableSkill {

    private static final SkillDefinition DEFINITION = new SkillDefinition(
            "coding-interview-coach",
            "根据编码题任务类型、难度和当前表现生成结构化教练策略。",
            SkillExecutionMode.WORKFLOW,
            List.of(),
            new SkillFailurePolicy(1, 600L, 0L, 5, 60000L, SkillFailureFallbackMode.SKIP_SKILL)
    );

    @Override
    public SkillDefinition definition() {
        return DEFINITION;
    }

    @Override
    public SkillExecutionResult execute(SkillExecutionContext context) {
        String taskType = context == null ? "" : context.text("taskType");
        String topic = context == null ? "" : context.text("topic");
        String difficulty = context == null ? "" : context.text("difficulty");
        String questionType = context == null ? "" : context.text("questionType");
        String question = context == null ? "" : context.text("question");
        String answer = context == null ? "" : context.text("answer");
        int score = context == null ? 0 : context.intValue("score", 0);

        String coachingSummary = switch (normalize(taskType)) {
            case "generate_question" -> buildQuestionGenerationGuide(topic, difficulty, questionType);
            case "evaluate_code" -> buildEvaluationGuide(topic, difficulty, questionType, answer);
            case "generate_follow_up" -> buildFollowUpGuide(topic, questionType, score, question, answer);
            default -> buildGenericGuide(topic, difficulty, questionType);
        };

        return SkillExecutionResult.success(
                DEFINITION.id(),
                Map.of(
                        "coachingSummary", coachingSummary,
                        "taskType", taskType,
                        "questionType", questionType
                ),
                1,
                List.of()
        );
    }

    private String buildQuestionGenerationGuide(String topic, String difficulty, String questionType) {
        String normalizedType = normalize(questionType);
        String shape = switch (normalizedType) {
            case "choice" -> "题干要短，选项要有迷惑性，但必须只有一个最佳答案。";
            case "fill" -> "留空点应对应核心 API、关键条件或复杂度结论，避免过长代码填空。";
            case "scenario" -> "题目要贴近真实工程场景，明确约束、输入输出和评判标准。";
            default -> "优先给出可独立作答的算法题，明确输入输出、约束、示例和复杂度关注点。";
        };
        return "【Coding Coach】\n- 任务: 出题\n- 主题: " + safeTopic(topic) + "\n- 难度: " + safeDifficulty(difficulty) + "\n- 题型: " + safeQuestionType(questionType) + "\n- 要求: " + shape;
    }

    private String buildEvaluationGuide(String topic, String difficulty, String questionType, String answer) {
        String completeness = answer == null || answer.isBlank()
                ? "答案为空或过短时优先指出缺失，不做过度推断。"
                : "优先按正确性、复杂度、边界条件、可读性四维评估。";
        String focus = "algorithm".equalsIgnoreCase(questionType)
                ? "尤其关注时间复杂度、空间复杂度和边界情况是否自洽。"
                : "尤其关注题意理解、关键判断依据和输出格式是否满足要求。";
        return "【Coding Coach】\n- 任务: 评估\n- 主题: " + safeTopic(topic) + "\n- 难度: " + safeDifficulty(difficulty) + "\n- 题型: " + safeQuestionType(questionType) + "\n- 要求: " + completeness + " " + focus;
    }

    private String buildFollowUpGuide(String topic, String questionType, int score, String question, String answer) {
        String progression;
        if (score >= 85) {
            progression = "提高难度，追问优化、trade-off、边界扩展或替代解法。";
        } else if (score >= 60) {
            progression = "平移追问，要求补充复杂度、边界条件或遗漏步骤。";
        } else {
            progression = "降级追问，回到基础思路、核心约束或最小可行解法。";
        }
        String anchor = question == null || question.isBlank()
                ? safeTopic(topic)
                : truncate(question.replaceAll("\\s+", " ").trim(), 80);
        String answerSignal = answer == null || answer.isBlank()
                ? "回答不足时不要发散到新主题。"
                : "追问必须紧贴当前题和已有回答。";
        return "【Coding Coach】\n- 任务: 追问\n- 题型: " + safeQuestionType(questionType) + "\n- 锚点: " + anchor + "\n- 要求: " + progression + " " + answerSignal;
    }

    private String buildGenericGuide(String topic, String difficulty, String questionType) {
        return "【Coding Coach】\n- 主题: " + safeTopic(topic) + "\n- 难度: " + safeDifficulty(difficulty) + "\n- 题型: " + safeQuestionType(questionType);
    }

    private String safeTopic(String topic) {
        return topic == null || topic.isBlank() ? "算法" : topic.trim();
    }

    private String safeDifficulty(String difficulty) {
        return difficulty == null || difficulty.isBlank() ? "medium" : difficulty.trim();
    }

    private String safeQuestionType(String questionType) {
        return questionType == null || questionType.isBlank() ? "ALGORITHM" : questionType.trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength) + "...";
    }
}

