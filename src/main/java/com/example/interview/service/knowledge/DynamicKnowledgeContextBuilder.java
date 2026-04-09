package com.example.interview.service.knowledge;

import com.example.interview.service.KnowledgeContextPacket;
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
        String newContext = buildBaseContext(newPacket);
        if (sessionId == null || sessionId.isBlank()) {
            return newContext;
        }

        return switch (analysis.dialogAct()) {
            case FOLLOW_UP, CLARIFICATION -> buildContinuationContext(analysis, newContext, sessionId);
            case NEW_QUESTION -> newContext;
            case RETURN -> buildReturnContext(analysis, newContext, sessionId);
            case COMPARISON -> buildComparisonContext(analysis, newContext, sessionId);
            case SUMMARY -> buildSummaryContext(newContext, sessionId);
        };
    }

    public String buildDialogSignal(TurnAnalysis analysis) {
        return switch (analysis.dialogAct()) {
            case FOLLOW_UP -> "用户正在对「" + analysis.currentTopic() + "」进行追问，请基于历史知识上下文和新检索结果给出连贯的深入解答。";
            case CLARIFICATION -> "用户请求澄清「" + analysis.currentTopic() + "」的某个细节，请结合历史知识给出更清晰的解释。";
            case NEW_QUESTION -> "用户切换到了新话题「" + analysis.currentTopic() + "」，请专注于新检索到的知识回答，不要引用之前话题的内容。";
            case RETURN -> "用户回跳到之前讨论过的话题「" + analysis.currentTopic() + "」，请结合之前该话题的知识摘要和新检索结果回答。";
            case COMPARISON -> "用户在对比「" + analysis.previousTopic() + "」和「" + analysis.currentTopic() + "」，请综合两个话题的知识进行对比分析。";
            case SUMMARY -> "用户请求总结对话中讨论过的知识，请综合所有历史话题的知识给出全面的总结。";
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

    private String buildComparisonContext(TurnAnalysis analysis, String newContext, String sessionId) {
        String previousDigest = topicTracker.getTopicKnowledgeDigest(sessionId, analysis.previousTopic());
        if (previousDigest == null || previousDigest.isBlank()) {
            return newContext;
        }
        return "【话题「" + analysis.previousTopic() + "」知识】\n" + previousDigest
                + "\n\n【话题「" + analysis.currentTopic() + "」知识】\n" + newContext;
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
}
