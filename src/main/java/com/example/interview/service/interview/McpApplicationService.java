package com.example.interview.service.interview;

import com.example.interview.service.McpGatewayService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class McpApplicationService {

    private final McpGatewayService mcpGatewayService;

    public McpApplicationService(McpGatewayService mcpGatewayService) {
        this.mcpGatewayService = mcpGatewayService;
    }

    public List<String> discoverCapabilities(String userId) {
        return mcpGatewayService.discoverCapabilities(userId);
    }

    public List<String> discoverCapabilities(String userId, String traceId) {
        return mcpGatewayService.discoverCapabilities(userId, traceId);
    }

    public Map<String, Object> invokeCapability(String userId, String capability, Map<String, Object> params, Map<String, Object> context) {
        return mcpGatewayService.invoke(userId, capability, params, context);
    }
}
