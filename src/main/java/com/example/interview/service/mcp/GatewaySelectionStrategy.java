package com.example.interview.service.mcp;

import com.example.interview.tool.FastMcpCapabilityGateway;
import com.example.interview.tool.DatabaseMcpAdapterRouter;
import com.example.interview.tool.McpBridgeCapabilityGateway;
import com.example.interview.tool.McpCapabilityGateway;
import com.example.interview.tool.McpGatewayException;
import com.example.interview.tool.McpSseCapabilityGateway;
import com.example.interview.tool.McpStdioCapabilityGateway;
import com.example.interview.tool.StubMcpCapabilityGateway;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GatewaySelectionStrategy {

    private final McpCapabilityGateway stubGateway;
    private final McpCapabilityGateway bridgeGateway;
    private final McpCapabilityGateway sseGateway;
    private final McpCapabilityGateway stdioGateway;
    private final McpCapabilityGateway fastMcpGateway;
    private final DatabaseMcpAdapterRouter databaseMcpAdapterRouter;

    public GatewaySelectionStrategy(StubMcpCapabilityGateway stubGateway,
                                    @Nullable McpBridgeCapabilityGateway bridgeGateway,
                                    @Nullable McpSseCapabilityGateway sseGateway,
                                    @Nullable McpStdioCapabilityGateway stdioGateway,
                                    @Nullable FastMcpCapabilityGateway fastMcpGateway,
                                    @Nullable DatabaseMcpAdapterRouter databaseMcpAdapterRouter) {
        this.stubGateway = stubGateway;
        this.bridgeGateway = bridgeGateway;
        this.sseGateway = sseGateway;
        this.stdioGateway = stdioGateway;
        this.fastMcpGateway = fastMcpGateway;
        this.databaseMcpAdapterRouter = databaseMcpAdapterRouter;
    }

    public McpCapabilityGateway primaryGateway(String mode) {
        String normalizedMode = mode == null ? "stub" : mode.trim().toLowerCase();
        if ("sse".equals(normalizedMode) && sseGateway != null) {
            return sseGateway;
        }
        if ("sse".equals(normalizedMode)) {
            throw new McpGatewayException("MCP_SSE_NOT_CONFIGURED", false, "mcp sse is not configured");
        }
        if ("bridge".equals(normalizedMode) && bridgeGateway != null) {
            return bridgeGateway;
        }
        if ("bridge".equals(normalizedMode)) {
            throw new McpGatewayException("MCP_BRIDGE_NOT_CONFIGURED", false, "mcp bridge is not configured");
        }
        if ("stdio".equals(normalizedMode) && stdioGateway != null) {
            return stdioGateway;
        }
        if ("stdio".equals(normalizedMode)) {
            throw new McpGatewayException("MCP_STDIO_NOT_CONFIGURED", false, "mcp stdio is not configured");
        }
        if ("fastmcp".equals(normalizedMode) && fastMcpGateway != null) {
            return fastMcpGateway;
        }
        if ("fastmcp".equals(normalizedMode)) {
            throw new McpGatewayException("MCP_FASTMCP_NOT_CONFIGURED", false, "mcp fastmcp is not configured");
        }
        if ("auto".equals(normalizedMode) && fastMcpGateway != null) {
            return fastMcpGateway;
        }
        if ("auto".equals(normalizedMode) && sseGateway != null) {
            return sseGateway;
        }
        if ("auto".equals(normalizedMode) && stdioGateway != null) {
            return stdioGateway;
        }
        if ("auto".equals(normalizedMode) && bridgeGateway != null) {
            return bridgeGateway;
        }
        return stubGateway;
    }

    public McpCapabilityGateway invokeGateway(String capability, String mode) {
        if (databaseMcpAdapterRouter != null && databaseMcpAdapterRouter.supports(capability)) {
            return databaseMcpAdapterRouter;
        }
        return primaryGateway(mode);
    }

    public List<String> databaseCapabilities() {
        if (databaseMcpAdapterRouter == null) {
            return List.of();
        }
        return databaseMcpAdapterRouter.listCapabilities();
    }
}
