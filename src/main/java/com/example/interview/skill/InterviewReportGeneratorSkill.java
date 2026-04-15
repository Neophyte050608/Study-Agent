package com.example.interview.skill;

import com.example.interview.core.Question;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class InterviewReportGeneratorSkill implements ExecutableSkill {

    private static final SkillDefinition DEFINITION = new SkillDefinition(
            "interview-report-generator",
            "基于整场面试记录生成结构化复盘策略，约束报告标签与优先级。",
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
        String targetedSuggestion = context == null ? "" : context.text("targetedSuggestion");
        String rollingSummary = context == null ? "" : context.text("rollingSummary");
        List<Question> history = extractHistory(context);
        String reportSummary = buildReportSummary(topic, history, targetedSuggestion, rollingSummary);
        return SkillExecutionResult.success(
                DEFINITION.id(),
                Map.of(
                        "reportSummary", reportSummary,
                        "averageScore", averageScore(history),
                        "weakQuestionCount", weakQuestionCount(history)
                ),
                1,
                List.of()
        );
    }

    private List<Question> extractHistory(SkillExecutionContext context) {
        if (context == null) {
            return List.of();
        }
        return context.list("history").stream()
                .filter(Question.class::isInstance)
                .map(Question.class::cast)
                .toList();
    }

    private String buildReportSummary(String topic,
                                      List<Question> history,
                                      String targetedSuggestion,
                                      String rollingSummary) {
        String normalizedTopic = topic == null || topic.isBlank() ? "后端基础" : topic.trim();
        int averageScore = averageScore(history);
        String interviewLevel = averageScore >= 80
                ? "整体表现较稳，复盘重点应放在高质量经验沉淀和更高阶追问。"
                : averageScore >= 60
                ? "整体表现中等，复盘重点应放在回答完整度、结构化表达和边界补齐。"
                : "整体表现偏弱，复盘重点应放在低分题纠错、基础原理补齐和重复薄弱点收敛。";
        String lowScoreQuestions = history.stream()
                .filter(item -> item.getScore() < 60)
                .sorted(Comparator.comparingInt(Question::getScore))
                .limit(3)
                .map(item -> safeQuestion(item) + "(" + item.getScore() + ")")
                .collect(Collectors.joining("、"));
        String incompleteQuestions = history.stream()
                .filter(item -> item.getScore() >= 60 && item.getScore() < 80)
                .limit(3)
                .map(InterviewReportGeneratorSkill::safeQuestion)
                .collect(Collectors.joining("、"));
        String strongQuestions = history.stream()
                .filter(item -> item.getScore() >= 80)
                .limit(2)
                .map(InterviewReportGeneratorSkill::safeQuestion)
                .collect(Collectors.joining("、"));

        return "【Interview Report Strategy】\n"
                + "- 主题: " + normalizedTopic + "\n"
                + "- 样本: " + history.size() + " 题 | 平均分: " + averageScore + "\n"
                + "- 基调: " + interviewLevel + "\n"
                + "- summary: 先给整体表现结论，再点出最关键的能力短板和下一步主线。\n"
                + "- incomplete: 只写“接近答对但没讲全”的题，优先包含 " + fallbackText(incompleteQuestions, "暂无明显中档缺口") + "。\n"
                + "- weak: 抽象能力层面的弱项，不复述题面，优先从表达结构、原理深度、边界意识中归纳。\n"
                + "- wrong: 只写明确错误或低分题，优先包含 " + fallbackText(lowScoreQuestions, "暂无明显错误题") + "。\n"
                + "- obsidian_updates: 只沉淀值得长期复用的机制、场景、源码点或易错结论；高分亮点可参考 "
                + fallbackText(strongQuestions, "暂无明显高光题") + "。\n"
                + "- next_focus: 优先承接 targetedSuggestion；若其为空，则围绕最低分主题给出 1-2 个具体动作。\n"
                + "- 约束: 严格输出 <summary><incomplete><weak><wrong><obsidian_updates><next_focus> 六段中文 XML，不要输出标签外解释。\n"
                + "- 外部输入: targetedSuggestion=" + fallbackText(targetedSuggestion, "无")
                + " | rollingSummary=" + (rollingSummary == null || rollingSummary.isBlank() ? "无" : "有历史基线，需与本轮结果保持一致");
    }

    private int averageScore(List<Question> history) {
        if (history == null || history.isEmpty()) {
            return 0;
        }
        return (int) Math.round(history.stream().mapToInt(Question::getScore).average().orElse(0D));
    }

    private int weakQuestionCount(List<Question> history) {
        if (history == null || history.isEmpty()) {
            return 0;
        }
        return (int) history.stream().filter(item -> item.getScore() < 60).count();
    }

    private static String safeQuestion(Question question) {
        if (question == null || question.getQuestionText() == null || question.getQuestionText().isBlank()) {
            return "未命名问题";
        }
        String text = question.getQuestionText().trim().replaceAll("\\s+", " ");
        return text.length() > 24 ? text.substring(0, 24) + "..." : text;
    }

    private String fallbackText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
