package com.example.interview.service;

import com.example.interview.tool.McpCapabilityGateway;
import com.example.interview.tool.McpGatewayException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpGatewayServiceTest {

    @Test
    void shouldFallbackToStubWhenBridgeInvokeFails() {
        McpCapabilityGateway stub = new McpCapabilityGateway() {
            @Override
            public List<String> listCapabilities() {
                return List.of("obsidian.write");
            }

            @Override
            public Object invokeCapability(String name, Map<String, Object> params) {
                return Map.of("status", "ok", "from", "stub");
            }
        };
        McpCapabilityGateway bridge = new McpCapabilityGateway() {
            @Override
            public List<String> listCapabilities() {
                return List.of("obsidian.write");
            }

            @Override
            public Object invokeCapability(String name, Map<String, Object> params) {
                throw new McpGatewayException("MCP_UNREACHABLE", true, "bridge down");
            }
        };
        McpGatewayService service = new McpGatewayService(
                stub,
                bridge,
                new OpsAuditService(),
                1000,
                1,
                "bridge",
                true
        );

        Map<String, Object> result = service.invoke(
                "tester",
                "obsidian.write",
                Map.of("topic", "java"),
                Map.of("traceId", "trace-1")
        );

        assertEquals("fallback_stub", result.get("status"));
        assertEquals("MCP_UNREACHABLE", result.get("errorCode"));
        assertTrue((Boolean) result.get("retryable"));
        assertTrue(result.get("result") instanceof Map);
    }

    @Test
    void shouldFallbackToStubWhenBridgeNotConfiguredInBridgeMode() {
        McpCapabilityGateway stub = new McpCapabilityGateway() {
            @Override
            public List<String> listCapabilities() {
                return List.of("obsidian.write", "code.execute");
            }

            @Override
            public Object invokeCapability(String name, Map<String, Object> params) {
                return Map.of("status", "ok", "from", "stub");
            }
        };
        McpGatewayService service = new McpGatewayService(
                stub,
                null,
                new OpsAuditService(),
                1000,
                0,
                "bridge",
                true
        );

        List<String> capabilities = service.discoverCapabilities("tester", "trace-2");
        Map<String, Object> result = service.invoke("tester", "obsidian.write", Map.of(), Map.of());

        assertEquals(2, capabilities.size());
        assertEquals("fallback_stub", result.get("status"));
        assertEquals("MCP_BRIDGE_NOT_CONFIGURED", result.get("errorCode"));
        assertFalse(result.get("timestamp").toString().isBlank());
    }

    @Test
    void shouldMarkPermissionDeniedAsNonRetryableAndAuditable() {
        McpCapabilityGateway stub = new McpCapabilityGateway() {
            @Override
            public List<String> listCapabilities() {
                return List.of("obsidian.write");
            }

            @Override
            public Object invokeCapability(String name, Map<String, Object> params) {
                return Map.of("status", "ok", "from", "stub");
            }
        };
        McpCapabilityGateway bridge = new McpCapabilityGateway() {
            @Override
            public List<String> listCapabilities() {
                return List.of("obsidian.write");
            }

            @Override
            public Object invokeCapability(String name, Map<String, Object> params) {
                throw new McpGatewayException("MCP_PERMISSION_DENIED", false, "permission denied");
            }
        };
        OpsAuditService auditService = new OpsAuditService();
        McpGatewayService service = new McpGatewayService(
                stub,
                bridge,
                auditService,
                1000,
                3,
                "bridge",
                true
        );

        Map<String, Object> result = service.invoke(
                "tester",
                "obsidian.write",
                Map.of("topic", "java"),
                Map.of("traceId", "trace-perm-1")
        );

        assertEquals("fallback_stub", result.get("status"));
        assertEquals("MCP_PERMISSION_DENIED", result.get("errorCode"));
        assertFalse((Boolean) result.get("retryable"));
        assertEquals(1, result.get("attempt"));
        List<OpsAuditService.OpsAuditRecord> records = auditService.listFiltered(20, "MCP_", "trace-perm-1", "MCP_PERMISSION_DENIED");
        assertEquals(1, records.size());
    }

    @Test
    void shouldUseSseGatewayWhenModeIsSse() {
        McpCapabilityGateway stub = gatewayFrom("stub");
        McpCapabilityGateway sse = gatewayFrom("sse");
        McpGatewayService service = new McpGatewayService(
                stub,
                null,
                sse,
                null,
                new OpsAuditService(),
                1000,
                0,
                "sse",
                true
        );

        Map<String, Object> result = service.invoke("tester", "obsidian.write", Map.of(), Map.of());

        assertEquals("ok", result.get("status"));
        assertTrue(result.get("result") instanceof Map);
        assertEquals("sse", ((Map<?, ?>) result.get("result")).get("from"));
    }

    @Test
    void shouldFallbackToStubWhenSseNotConfiguredInSseMode() {
        McpCapabilityGateway stub = gatewayFrom("stub");
        McpGatewayService service = new McpGatewayService(
                stub,
                null,
                null,
                null,
                new OpsAuditService(),
                1000,
                0,
                "sse",
                true
        );

        Map<String, Object> result = service.invoke("tester", "obsidian.write", Map.of(), Map.of());

        assertEquals("fallback_stub", result.get("status"));
        assertEquals("MCP_SSE_NOT_CONFIGURED", result.get("errorCode"));
    }

    @Test
    void shouldPreferSseInAutoModeWhenSseAndBridgeBothExist() {
        McpCapabilityGateway stub = gatewayFrom("stub");
        McpCapabilityGateway bridge = gatewayFrom("bridge");
        McpCapabilityGateway sse = gatewayFrom("sse");
        McpGatewayService service = new McpGatewayService(
                stub,
                bridge,
                sse,
                null,
                new OpsAuditService(),
                1000,
                0,
                "auto",
                true
        );

        Map<String, Object> result = service.invoke("tester", "obsidian.write", Map.of(), Map.of());

        assertEquals("ok", result.get("status"));
        assertEquals("sse", ((Map<?, ?>) result.get("result")).get("from"));
    }

    @Test
    void shouldUseStdioGatewayWhenModeIsStdio() {
        McpCapabilityGateway stub = gatewayFrom("stub");
        McpCapabilityGateway stdio = gatewayFrom("stdio");
        McpGatewayService service = new McpGatewayService(
                stub,
                null,
                null,
                stdio,
                new OpsAuditService(),
                1000,
                0,
                "stdio",
                true
        );

        Map<String, Object> result = service.invoke("tester", "obsidian.write", Map.of(), Map.of());

        assertEquals("ok", result.get("status"));
        assertEquals("stdio", ((Map<?, ?>) result.get("result")).get("from"));
    }

    @Test
    void shouldFallbackToStubWhenStdioNotConfiguredInStdioMode() {
        McpCapabilityGateway stub = gatewayFrom("stub");
        McpGatewayService service = new McpGatewayService(
                stub,
                null,
                null,
                null,
                new OpsAuditService(),
                1000,
                0,
                "stdio",
                true
        );

        Map<String, Object> result = service.invoke("tester", "obsidian.write", Map.of(), Map.of());

        assertEquals("fallback_stub", result.get("status"));
        assertEquals("MCP_STDIO_NOT_CONFIGURED", result.get("errorCode"));
    }

    @Test
    void shouldPreferStdioInAutoModeWhenSseMissing() {
        McpCapabilityGateway stub = gatewayFrom("stub");
        McpCapabilityGateway bridge = gatewayFrom("bridge");
        McpCapabilityGateway stdio = gatewayFrom("stdio");
        McpGatewayService service = new McpGatewayService(
                stub,
                bridge,
                null,
                stdio,
                new OpsAuditService(),
                1000,
                0,
                "auto",
                true
        );

        Map<String, Object> result = service.invoke("tester", "obsidian.write", Map.of(), Map.of());

        assertEquals("ok", result.get("status"));
        assertEquals("stdio", ((Map<?, ?>) result.get("result")).get("from"));
    }

    @Test
    void shouldReturnFallbackWhenStdioFailsAndFallbackDisabled() {
        McpCapabilityGateway stub = gatewayFrom("stub");
        McpCapabilityGateway stdio = new McpCapabilityGateway() {
            @Override
            public List<String> listCapabilities() {
                return List.of("obsidian.write");
            }

            @Override
            public Object invokeCapability(String name, Map<String, Object> params) {
                throw new McpGatewayException("MCP_UNREACHABLE", true, "stdio down");
            }
        };
        McpGatewayService service = new McpGatewayService(
                stub,
                null,
                null,
                stdio,
                new OpsAuditService(),
                1000,
                0,
                "stdio",
                false
        );

        Map<String, Object> result = service.invoke("tester", "obsidian.write", Map.of(), Map.of());

        assertEquals("fallback", result.get("status"));
        assertEquals("MCP_UNREACHABLE", result.get("errorCode"));
        assertTrue((Boolean) result.get("retryable"));
    }

    @Test
    void shouldFallbackToStubWhenStdioFailsInAutoMode() {
        McpCapabilityGateway stub = gatewayFrom("stub");
        McpCapabilityGateway stdio = new McpCapabilityGateway() {
            @Override
            public List<String> listCapabilities() {
                return List.of("obsidian.write");
            }

            @Override
            public Object invokeCapability(String name, Map<String, Object> params) {
                throw new McpGatewayException("MCP_TIMEOUT", true, "stdio timeout");
            }
        };
        McpGatewayService service = new McpGatewayService(
                stub,
                gatewayFrom("bridge"),
                null,
                stdio,
                new OpsAuditService(),
                1000,
                0,
                "auto",
                true
        );

        Map<String, Object> result = service.invoke("tester", "obsidian.write", Map.of(), Map.of());

        assertEquals("fallback_stub", result.get("status"));
        assertEquals("MCP_TIMEOUT", result.get("errorCode"));
        assertTrue((Boolean) result.get("retryable"));
    }

    private McpCapabilityGateway gatewayFrom(String source) {
        return new McpCapabilityGateway() {
            @Override
            public List<String> listCapabilities() {
                return List.of("obsidian.write");
            }

            @Override
            public Object invokeCapability(String name, Map<String, Object> params) {
                return Map.of("status", "ok", "from", source);
            }
        };
    }
}
