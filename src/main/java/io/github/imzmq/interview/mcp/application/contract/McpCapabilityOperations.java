package io.github.imzmq.interview.mcp.application.contract;

import java.util.List;
import java.util.Map;

/** Public MCP capability discovery and invocation surface. */
public interface McpCapabilityOperations {

    List<String> discoverCapabilities(String operator);

    List<String> discoverCapabilities(String operator, String traceId);

    Map<String, Object> invoke(String operator, String capability, Map<String, Object> params, Map<String, Object> context);
}
