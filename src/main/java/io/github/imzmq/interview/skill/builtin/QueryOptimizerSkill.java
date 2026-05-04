package io.github.imzmq.interview.skill.builtin;

import io.github.imzmq.interview.modelrouting.core.ModelRouteType;
import io.github.imzmq.interview.modelrouting.core.RoutingChatService;
import io.github.imzmq.interview.modelrouting.core.TimeoutHint;
import io.github.imzmq.interview.agent.application.AgentSkillService;
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
import io.github.imzmq.interview.common.api.BusinessException;
import io.github.imzmq.interview.common.api.ErrorCode;

@Component
public class QueryOptimizerSkill implements ExecutableSkill {

    private static final SkillDefinition DEFINITION = new SkillDefinition(
            "query-optimizer",
            "重写检索查询，输出核心关键词与扩展关键词。",
            SkillExecutionMode.AGENTIC,
            List.of(),
            new SkillFailurePolicy(2, 1800L, 200L, 5, 60000L, SkillFailureFallbackMode.SKIP_SKILL)
    );

    private final RoutingChatService routingChatService;
    private final AgentSkillService agentSkillService;

    public QueryOptimizerSkill(RoutingChatService routingChatService, AgentSkillService agentSkillService) {
        this.routingChatService = routingChatService;
        this.agentSkillService = agentSkillService;
    }

    @Override
    public SkillDefinition definition() {
        return DEFINITION;
    }

    @Override
    public SkillExecutionResult execute(SkillExecutionContext context) {
        String question = context == null ? "" : context.text("question");
        String userAnswer = context == null ? "" : context.text("userAnswer");
        if (question.isBlank() && userAnswer.isBlank()) {
            throw new BusinessException(ErrorCode.SKILL_INPUT_MISSING);
        }
        String skillBlock = safeSkillText(agentSkillService.resolveSkillBlock("query-optimizer"));
        String prompt = skillBlock + "\n" +
                "请从下面的面试问答中提取用于知识检索的关键词。\n" +
                "严格按 CORE/EXPAND 两行格式输出。\n" +
                "问题：" + question + "\n" +
                "回答：" + userAnswer + "\n" +
                "只返回 CORE 和 EXPAND 两行，不要返回其他解释。";
        String raw = routingChatService.callWithFirstPacketProbeSupplier(
                () -> normalizeRewriteSource(question, userAnswer),
                prompt,
                ModelRouteType.GENERAL,
                TimeoutHint.NORMAL,
                "关键词提取"
        );
        ParsedRewrite parsed = parse(raw, normalizeRewriteSource(question, userAnswer));
        return SkillExecutionResult.success(
                DEFINITION.id(),
                Map.of(
                        "coreTerms", parsed.coreTerms(),
                        "expandTerms", parsed.expandTerms(),
                        "fullQuery", parsed.fullQuery()
                ),
                1,
                List.of()
        );
    }

    private ParsedRewrite parse(String raw, String fallbackRaw) {
        if (raw == null || raw.isBlank()) {
            return new ParsedRewrite(fallbackRaw, "", fallbackRaw);
        }
        String core = "";
        String expand = "";
        for (String line : raw.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.toUpperCase(Locale.ROOT).startsWith("CORE:")) {
                core = trimmed.substring(5).trim();
            } else if (trimmed.toUpperCase(Locale.ROOT).startsWith("EXPAND:")) {
                expand = trimmed.substring(7).trim();
            }
        }
        if (core.isBlank()) {
            String normalized = raw.replaceAll("\\s+", " ").trim();
            String fallback = normalized.isBlank() ? fallbackRaw : normalized;
            return new ParsedRewrite(fallback, "", fallback);
        }
        String fullQuery = expand.isBlank() ? core : core + " " + expand;
        return new ParsedRewrite(core, expand, fullQuery);
    }

    private String safeSkillText(String content) {
        return content == null || content.isBlank() ? "" : content.trim() + "\n";
    }

    private String normalizeRewriteSource(String question, String userAnswer) {
        String combined = ((question == null ? "" : question.trim()) + " " + (userAnswer == null ? "" : userAnswer.trim())).trim();
        return combined.replaceAll("\\s+", " ");
    }

    private record ParsedRewrite(String coreTerms, String expandTerms, String fullQuery) {
    }
}



