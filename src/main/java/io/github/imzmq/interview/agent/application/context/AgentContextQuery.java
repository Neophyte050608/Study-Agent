package io.github.imzmq.interview.agent.application.context;

import java.util.Map;

public record AgentContextQuery(
        AgentContextMode mode,
        String query,
        Map<String, Object> attributes
) {
    public AgentContextQuery {
        mode = mode == null ? AgentContextMode.KNOWLEDGE_QA : mode;
        query = query == null ? "" : query;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static AgentContextQuery create(AgentContextMode mode, String query, Map<String, Object> attributes) {
        return new AgentContextQuery(mode, query, attributes);
    }

    public Object attribute(String key) {
        return attributes.get(key);
    }
}
