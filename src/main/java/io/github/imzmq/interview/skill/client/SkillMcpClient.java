package io.github.imzmq.interview.skill.client;

import io.github.imzmq.interview.mcp.application.McpGatewayService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import io.github.imzmq.interview.skill.core.SkillDefinition;
import io.github.imzmq.interview.skill.core.SkillExecutionContext;

@Service
public class SkillMcpClient {

    private final McpGatewayService mcpGatewayService;

    public SkillMcpClient(McpGatewayService mcpGatewayService) {
        this.mcpGatewayService = mcpGatewayService;
    }

    public Map<String, Object> invokeForSkill(String operator,
                                              SkillDefinition definition,
                                              SkillExecutionContext context,
                                              String capability,
                                              Map<String, Object> params) {
        String normalizedCapability = capability == null ? "" : capability.trim().toLowerCase(Locale.ROOT);
        if (definition == null || normalizedCapability.isBlank()) {
            return blocked("skill_or_capability_missing");
        }
        List<String> allowed = definition.allowedMcpCapabilities() == null ? List.of() : definition.allowedMcpCapabilities();
        boolean permitted = allowed.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(item -> item.trim().toLowerCase(Locale.ROOT))
                .anyMatch(normalizedCapability::equals);
        if (!permitted) {
            return blocked("capability_not_allowed");
        }
        if (context != null && context.budget() != null && !context.budget().tryConsumeMcpCall()) {
            return blocked("mcp_budget_exhausted");
        }
        Map<String, Object> invokeContext = new LinkedHashMap<>();
        if (context != null) {
            if (context.traceId() != null && !context.traceId().isBlank()) {
                invokeContext.put("traceId", context.traceId());
            }
            if (context.operator() != null && !context.operator().isBlank()) {
                invokeContext.put("operator", context.operator());
            }
        }
        invokeContext.put("skillId", definition.id());
        invokeContext.put("source", "SkillMcpClient");
        Map<String, Object> response = mcpGatewayService.invoke(
                operator == null || operator.isBlank() ? "anonymous" : operator,
                normalizedCapability,
                params == null ? Map.of() : params,
                invokeContext
        );
        return response == null ? blocked("mcp_empty_response") : response;
    }

    private Map<String, Object> blocked(String reason) {
        return Map.of(
                "status", "blocked",
                "message", reason,
                "result", Map.of()
        );
    }
}


