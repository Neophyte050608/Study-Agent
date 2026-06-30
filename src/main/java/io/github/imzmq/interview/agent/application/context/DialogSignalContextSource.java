package io.github.imzmq.interview.agent.application.context;

import io.github.imzmq.interview.knowledge.domain.TurnAnalysis;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class DialogSignalContextSource implements AgentContextSource {

    @Override
    public String id() {
        return "dialog-signal";
    }

    @Override
    public boolean supports(AgentContextSlotKind kind) {
        return kind == AgentContextSlotKind.DIALOG_SIGNAL;
    }

    @Override
    public List<ContextItem> fetch(AgentContextSlot slot, AgentContextQuery query) {
        TurnAnalysis analysis = KnowledgeQaContextAttributes.analysis(query);
        if (analysis == null) {
            return List.of();
        }
        String contextPolicy = KnowledgeQaContextAttributes.text(query, KnowledgeQaContextAttributes.CONTEXT_POLICY);
        String signal = buildDialogSignal(contextPolicy, analysis);
        if (signal.isBlank()) {
            return List.of();
        }
        return List.of(new ContextItem(signal, 1.0, id(), Map.of("contextPolicy", contextPolicy)));
    }

    private String buildDialogSignal(String contextPolicy, TurnAnalysis analysis) {
        String policy = normalizePolicy(contextPolicy, analysis);
        return switch (policy) {
            case "CONTINUE" -> "用户正在对「" + analysis.currentTopic() + "」进行追问，请基于历史知识上下文和新检索结果给出连贯的深入解答。";
            case "SWITCH" -> "用户切换到了新话题「" + analysis.currentTopic() + "」，请专注于新检索到的知识回答，不要引用之前话题的内容。";
            case "RETURN" -> "用户回跳到之前讨论过的话题「" + analysis.currentTopic() + "」，请结合之前该话题的知识摘要和新检索结果回答。";
            case "SUMMARY" -> "用户请求总结对话中讨论过的知识，请综合所有历史话题的知识给出全面的总结。";
            case "SAFE_MIN" -> "当前轮请以本轮检索结果为主，谨慎引用历史信息，优先保证回答准确与收敛。";
            default -> "";
        };
    }

    private String normalizePolicy(String contextPolicy, TurnAnalysis analysis) {
        String value = contextPolicy == null ? "" : contextPolicy.trim().toUpperCase();
        if ("CONTINUE".equals(value) || "SWITCH".equals(value) || "RETURN".equals(value)
                || "SUMMARY".equals(value) || "SAFE_MIN".equals(value)) {
            return value;
        }
        return switch (analysis.dialogAct()) {
            case NEW_QUESTION, COMPARISON -> "SWITCH";
            case RETURN -> "RETURN";
            case SUMMARY -> "SUMMARY";
            case FOLLOW_UP, CLARIFICATION -> analysis.topicSwitch() ? "SWITCH" : "CONTINUE";
        };
    }
}
