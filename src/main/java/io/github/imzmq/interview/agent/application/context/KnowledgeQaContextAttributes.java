package io.github.imzmq.interview.agent.application.context;

import io.github.imzmq.interview.knowledge.domain.KnowledgeContextPacket;
import io.github.imzmq.interview.knowledge.domain.TurnAnalysis;

public final class KnowledgeQaContextAttributes {
    public static final String PACKET = "knowledgeQa.packet";
    public static final String ANALYSIS = "knowledgeQa.analysis";
    public static final String CONTEXT_POLICY = "knowledgeQa.contextPolicy";
    public static final String SESSION_ID = "knowledgeQa.sessionId";
    public static final String USER_ID = "knowledgeQa.userId";
    public static final String CURRENT_TOPIC = "knowledgeQa.currentTopic";

    private KnowledgeQaContextAttributes() {
    }

    public static KnowledgeContextPacket packet(AgentContextQuery query) {
        Object value = query == null ? null : query.attribute(PACKET);
        return value instanceof KnowledgeContextPacket packet ? packet : null;
    }

    public static TurnAnalysis analysis(AgentContextQuery query) {
        Object value = query == null ? null : query.attribute(ANALYSIS);
        return value instanceof TurnAnalysis analysis ? analysis : null;
    }

    public static String text(AgentContextQuery query, String key) {
        Object value = query == null ? null : query.attribute(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
