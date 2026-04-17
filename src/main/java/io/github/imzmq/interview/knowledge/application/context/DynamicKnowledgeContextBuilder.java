package io.github.imzmq.interview.knowledge.application.context;

import io.github.imzmq.interview.knowledge.domain.KnowledgeContextPacket;
import io.github.imzmq.interview.knowledge.domain.TopicState;
import io.github.imzmq.interview.knowledge.domain.TurnAnalysis;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DynamicKnowledgeContextBuilder {

    private final ConversationTopicTracker topicTracker;

    public DynamicKnowledgeContextBuilder(ConversationTopicTracker topicTracker) {
        this.topicTracker = topicTracker;
    }

    public String buildDynamicContext(TurnAnalysis analysis,
                                      KnowledgeContextPacket newPacket,
                                      String sessionId) {
        return buildDynamicContext(resolvePolicyFromAnalysis(analysis), analysis, newPacket, sessionId);
    }

    public String buildDynamicContext(String contextPolicy,
                                      TurnAnalysis analysis,
                                      KnowledgeContextPacket newPacket,
                                      String sessionId) {
        String newContext = buildBaseContext(newPacket);
        if (sessionId == null || sessionId.isBlank()) {
            return newContext;
        }
        String policy = normalizePolicy(contextPolicy, analysis);
        return switch (policy) {
            case "CONTINUE" -> buildContinuationContext(analysis, newContext, sessionId);
            case "SWITCH" -> newContext;
            case "RETURN" -> buildReturnContext(analysis, newContext, sessionId);
            case "SUMMARY" -> buildSummaryContext(newContext, sessionId);
            case "SAFE_MIN" -> buildSafeMinimalContext(newContext);
            default -> newContext;
        };
    }

    public String buildDialogSignal(TurnAnalysis analysis) {
        return buildDialogSignal(resolvePolicyFromAnalysis(analysis), analysis);
    }

    public String buildDialogSignal(String contextPolicy, TurnAnalysis analysis) {
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

    private String buildBaseContext(KnowledgeContextPacket packet) {
        StringBuilder contextBuilder = new StringBuilder(packet.context() == null ? "" : packet.context());
        if (packet.imageContext() != null && !packet.imageContext().isBlank()) {
            contextBuilder.append("\n\n相关图片说明:\n")
                    .append(packet.imageContext())
                    .append("\n注意：你的回答可以引用上述图片，使用 [图N] 标记。系统会自动将对应图片内联展示给用户。");
        }
        return contextBuilder.toString();
    }

    private String buildContinuationContext(TurnAnalysis analysis, String newContext, String sessionId) {
        String digest = topicTracker.getTopicKnowledgeDigest(sessionId, analysis.currentTopic());
        if (digest == null || digest.isBlank()) {
            return newContext;
        }
        return "【历史知识摘要】\n" + digest + "\n\n【本轮检索】\n" + newContext;
    }

    private String buildReturnContext(TurnAnalysis analysis, String newContext, String sessionId) {
        String digest = topicTracker.getTopicKnowledgeDigest(sessionId, analysis.currentTopic());
        if (digest == null || digest.isBlank()) {
            return newContext;
        }
        return "【回跳话题知识】\n" + digest + "\n\n【补充检索】\n" + newContext;
    }

    private String buildSafeMinimalContext(String newContext) {
        return newContext;
    }

    private String buildSummaryContext(String newContext, String sessionId) {
        List<TopicState> allTopics = topicTracker.getRecentTopics(sessionId, 10);
        if (allTopics.isEmpty()) {
            return newContext;
        }

        StringBuilder summary = new StringBuilder("【历史知识汇总】\n");
        for (TopicState topic : allTopics) {
            if (topic.knowledgeDigest() != null && !topic.knowledgeDigest().isBlank()) {
                summary.append("▶ ")
                        .append(topic.topicId())
                        .append(":\n")
                        .append(topic.knowledgeDigest())
                        .append("\n\n");
            }
        }
        summary.append("【补充检索】\n").append(newContext);
        return summary.toString();
    }

    private String resolvePolicyFromAnalysis(TurnAnalysis analysis) {
        if (analysis == null) {
            return "SAFE_MIN";
        }
        return switch (analysis.dialogAct()) {
            case NEW_QUESTION, COMPARISON -> "SWITCH";
            case RETURN -> "RETURN";
            case SUMMARY -> "SUMMARY";
            case FOLLOW_UP, CLARIFICATION -> analysis.topicSwitch() ? "SWITCH" : "CONTINUE";
        };
    }

    private String normalizePolicy(String contextPolicy, TurnAnalysis analysis) {
        String value = contextPolicy == null ? "" : contextPolicy.trim().toUpperCase();
        if ("CONTINUE".equals(value)
                || "SWITCH".equals(value)
                || "RETURN".equals(value)
                || "SUMMARY".equals(value)
                || "SAFE_MIN".equals(value)) {
            return value;
        }
        return resolvePolicyFromAnalysis(analysis);
    }
}





