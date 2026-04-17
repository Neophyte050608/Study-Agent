package io.github.imzmq.interview.tool.gateway;

import java.util.List;
import java.util.Map;

/**
 * MCP 能力网关抽象。
 *
 * <p>用于屏蔽不同接入方式（stub/bridge/sse/stdio）的差异，统一提供：</p>
 * <ul>
 *   <li>listCapabilities：能力发现</li>
 *   <li>invokeCapability：能力调用（可选携带 context，用于 traceId、用户信息等透传）</li>
 * </ul>
 */
public interface McpCapabilityGateway {
    List<String> listCapabilities();

    Object invokeCapability(String name, Map<String, Object> params);

    default Object invokeCapability(String name, Map<String, Object> params, Map<String, Object> context) {
        return invokeCapability(name, params);
    }
}

