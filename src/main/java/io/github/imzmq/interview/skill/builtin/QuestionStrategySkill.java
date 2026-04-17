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
public class QuestionStrategySkill implements ExecutableSkill {

    private static final SkillDefinition DEFINITION = new SkillDefinition(
            "question-strategy",
            "根据题目阶段、主题和画像生成结构化提问策略。",
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
        String stage = context == null ? "" : context.text("stage");
        String topic = context == null ? "" : context.text("topic");
        String question = context == null ? "" : context.text("question");
        String difficulty = context == null ? "" : context.text("difficultyLevel");
        String followUpState = context == null ? "" : context.text("followUpState");
        String profileSnapshot = context == null ? "" : context.text("profileSnapshot");
        String resumeContent = context == null ? "" : context.text("resumeContent");
        boolean skipIntro = context != null && context.bool("skipIntro");

        String strategySummary = switch (normalize(stage)) {
            case "evaluation" -> buildEvaluationStrategy(topic, question, difficulty, followUpState, profileSnapshot);
            case "first-question" -> buildFirstQuestionStrategy(topic, profileSnapshot, resumeContent, skipIntro);
            default -> buildGenericStrategy(topic, profileSnapshot);
        };

        return SkillExecutionResult.success(
                DEFINITION.id(),
                Map.of(
                        "strategySummary", strategySummary,
                        "stage", stage,
                        "topic", topic
                ),
                1,
                List.of()
        );
    }

    private String buildFirstQuestionStrategy(String topic,
                                              String profileSnapshot,
                                              String resumeContent,
                                              boolean skipIntro) {
        String normalizedTopic = safeTopic(topic);
        String questionMode;
        if (!skipIntro) {
            questionMode = "先用简短自我介绍开场，再快速切到技术主题，避免一上来问得过深。";
        } else if (looksCodingTopic(normalizedTopic)) {
            questionMode = "直接进入编码思路题，先问解法框架，再根据边界条件追问。";
        } else if (looksProjectHeavy(profileSnapshot, resumeContent)) {
            questionMode = "优先从真实项目经验切入，围绕 " + normalizedTopic + " 让候选人先讲场景和设计权衡。";
        } else {
            questionMode = "先从 " + normalizedTopic + " 的核心概念或原理切入，再根据回答深度递进。";
        }
        String difficultyHint = looksSenior(profileSnapshot, resumeContent)
                ? "问题深度偏中高阶，优先考察原理、取舍和故障处理。"
                : "问题深度先控制在基础到中阶，确认概念准确性后再升级。";
        return "【Question Strategy】\n- 阶段: 首题生成\n- 主题: " + normalizedTopic + "\n- 策略: " + questionMode + "\n- 深度: " + difficultyHint;
    }

    private String buildEvaluationStrategy(String topic,
                                           String question,
                                           String difficulty,
                                           String followUpState,
                                           String profileSnapshot) {
        String normalizedTopic = safeTopic(topic);
        String depth = normalize(difficulty).contains("advanced") || normalize(difficulty).contains("hard")
                ? "高阶评估，重点看原理完整度、边界和工程取舍。"
                : "常规评估，重点看概念准确度、逻辑结构和示例支撑。";
        String followUpHint = normalize(followUpState).contains("probe")
                ? "当前是追问态，优先检查候选人是否补齐原回答中的漏洞。"
                : "当前是主问题，优先检查回答是否建立完整主线。";
        String profileHint = looksSenior(profileSnapshot, "")
                ? "允许对项目化表达和复杂场景有更高要求。"
                : "对表达不做过高工程化要求，先看核心知识是否站得住。";
        String focus = question == null || question.isBlank()
                ? normalizedTopic
                : question.trim();
        return "【Question Strategy】\n- 阶段: 回答评估\n- 焦点: " + focus + "\n- 主题: " + normalizedTopic + "\n- 策略: " + depth + " " + followUpHint + " " + profileHint;
    }

    private String buildGenericStrategy(String topic, String profileSnapshot) {
        return "【Question Strategy】\n- 阶段: 通用\n- 主题: " + safeTopic(topic) + "\n- 策略: " +
                (looksSenior(profileSnapshot, "") ? "优先看原理和权衡。" : "优先看基础概念和表达完整度。");
    }

    private boolean looksCodingTopic(String topic) {
        String normalized = normalize(topic);
        return normalized.contains("算法")
                || normalized.contains("代码")
                || normalized.contains("coding")
                || normalized.contains("sql")
                || normalized.contains("数据库题");
    }

    private boolean looksProjectHeavy(String profileSnapshot, String resumeContent) {
        String merged = normalize(profileSnapshot + " " + resumeContent);
        return merged.contains("项目")
                || merged.contains("实战")
                || merged.contains("架构")
                || merged.contains("负责")
                || merged.contains("落地");
    }

    private boolean looksSenior(String profileSnapshot, String resumeContent) {
        String merged = normalize(profileSnapshot + " " + resumeContent);
        return merged.contains("高级")
                || merged.contains("资深")
                || merged.contains("架构")
                || merged.contains("负责人")
                || merged.contains("lead")
                || merged.contains("senior");
    }

    private String safeTopic(String topic) {
        return topic == null || topic.isBlank() ? "后端基础" : topic.trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}

