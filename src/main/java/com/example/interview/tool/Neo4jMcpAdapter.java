package com.example.interview.tool;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "app.mcp.database.neo4j", name = "enabled", havingValue = "true")
public class Neo4jMcpAdapter implements DatabaseMcpAdapter {
    @Override
    public String namespace() {
        return "neo4j";
    }

    @Override
    public List<String> capabilities() {
        return List.of("neo4j.schema", "neo4j.query");
    }

    @Override
    public Object invoke(String capability, Map<String, Object> params, Map<String, Object> context) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "placeholder");
        response.put("adapter", "neo4j");
        response.put("capability", capability == null ? "" : capability.trim());
        response.put("params", params == null ? Map.of() : params);
        response.put("context", context == null ? Map.of() : context);
        response.put("timestamp", Instant.now().toString());
        return response;
    }
}
