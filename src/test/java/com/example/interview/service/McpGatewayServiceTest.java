package com.example.interview.service;

import com.example.interview.tool.McpCapabilityGateway;
import com.example.interview.tool.DatabaseMcpAdapter;
import com.example.interview.tool.DatabaseMcpAdapterRouter;
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
    void shouldUseFastMcpGatewayWhenModeIsFastMcp() {
        McpCapabilityGateway stub = gatewayFrom("stub");
        McpCapabilityGateway fastMcp = gatewayFrom("fastmcp");
        McpGatewayService service = new McpGatewayService(
                stub,
                null,
                null,
                null,
                fastMcp,
                new OpsAuditService(),
                1000,
                0,
                "fastmcp",
                true
        );

        Map<String, Object> result = service.invoke("tester", "obsidian.write", Map.of(), Map.of());

        assertEquals("ok", result.get("status"));
        assertEquals("fastmcp", ((Map<?, ?>) result.get("result")).get("from"));
    }

    @Test
    void shouldPreferFastMcpInAutoModeWhenAvailable() {
        McpCapabilityGateway stub = gatewayFrom("stub");
        McpCapabilityGateway fastMcp = gatewayFrom("fastmcp");
        McpGatewayService service = new McpGatewayService(
                stub,
                gatewayFrom("bridge"),
                gatewayFrom("sse"),
                gatewayFrom("stdio"),
                fastMcp,
                new OpsAuditService(),
                1000,
                0,
                "auto",
                true
        );

        Map<String, Object> result = service.invoke("tester", "obsidian.write", Map.of(), Map.of());

        assertEquals("ok", result.get("status"));
        assertEquals("fastmcp", ((Map<?, ?>) result.get("result")).get("from"));
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

    @Test
    void shouldNormalizeReadRangeParamsForObsidianRead() {
        McpCapabilityGateway stub = new McpCapabilityGateway() {
            @Override
            public List<String> listCapabilities() {
                return List.of("obsidian.read");
            }

            @Override
            public Object invokeCapability(String name, Map<String, Object> params) {
                return Map.of("status", "ok", "from", "stub", "params", params == null ? Map.of() : params);
            }
        };
        McpGatewayService service = new McpGatewayService(
                stub,
                null,
                null,
                null,
                new OpsAuditService(),
                1000,
                0,
                "stub",
                true
        );

        Map<String, Object> result = service.invoke(
                "tester",
                "obsidian.read",
                Map.of("lineStart", 5, "lineEnd", 8),
                Map.of()
        );

        assertEquals("ok", result.get("status"));
        Object rawResult = result.get("result");
        assertTrue(rawResult instanceof Map);
        Object rawParams = ((Map<?, ?>) rawResult).get("params");
        assertTrue(rawParams instanceof Map);
        assertEquals(5, ((Map<?, ?>) rawParams).get("offset"));
        assertEquals(4, ((Map<?, ?>) rawParams).get("limit"));
    }

    @Test
    void shouldRejectInvalidReadLimitBeforeGatewayInvoke() {
        McpCapabilityGateway stub = gatewayFrom("stub");
        McpGatewayService service = new McpGatewayService(
                stub,
                null,
                null,
                null,
                new OpsAuditService(),
                1000,
                0,
                "stub",
                true
        );

        Map<String, Object> result = service.invoke(
                "tester",
                "obsidian.read",
                Map.of("offset", 1, "limit", 0),
                Map.of()
        );

        assertEquals("fallback", result.get("status"));
        assertEquals("MCP_INVALID_PARAMS", result.get("errorCode"));
        assertFalse((Boolean) result.get("retryable"));
    }

    @Test
    void shouldRouteDatabaseCapabilityToAdapterRouter() {
        McpCapabilityGateway stub = gatewayFrom("stub");
        DatabaseMcpAdapter adapter = new DatabaseMcpAdapter() {
            @Override
            public String namespace() {
                return "neo4j";
            }

            @Override
            public List<String> capabilities() {
                return List.of("neo4j.query");
            }

            @Override
            public Object invoke(String capability, Map<String, Object> params, Map<String, Object> context) {
                return Map.of("status", "ok", "from", "neo4j-adapter", "capability", capability);
            }
        };
        DatabaseMcpAdapterRouter router = new DatabaseMcpAdapterRouter(List.of(adapter));
        McpGatewayService service = new McpGatewayService(
                stub,
                gatewayFrom("bridge"),
                gatewayFrom("sse"),
                gatewayFrom("stdio"),
                gatewayFrom("fastmcp"),
                router,
                new OpsAuditService(),
                1000,
                0,
                "auto",
                true
        );

        Map<String, Object> result = service.invoke("tester", "neo4j.query", Map.of("query", "MATCH (n) RETURN n"), Map.of());

        assertEquals("ok", result.get("status"));
        assertTrue(result.get("result") instanceof Map);
        assertEquals("neo4j-adapter", ((Map<?, ?>) result.get("result")).get("from"));
    }

    @Test
    void shouldAppendDatabaseCapabilitiesToDiscoverResult() {
        McpCapabilityGateway stub = gatewayFrom("stub");
        DatabaseMcpAdapter adapter = new DatabaseMcpAdapter() {
            @Override
            public String namespace() {
                return "milvus";
            }

            @Override
            public List<String> capabilities() {
                return List.of("milvus.search", "milvus.collection.list");
            }

            @Override
            public Object invoke(String capability, Map<String, Object> params, Map<String, Object> context) {
                return Map.of();
            }
        };
        DatabaseMcpAdapterRouter router = new DatabaseMcpAdapterRouter(List.of(adapter));
        McpGatewayService service = new McpGatewayService(
                stub,
                null,
                null,
                null,
                null,
                router,
                new OpsAuditService(),
                1000,
                0,
                "stub",
                true
        );

        List<String> capabilities = service.discoverCapabilities("tester", "trace-db-cap");

        assertTrue(capabilities.contains("obsidian.write"));
        assertTrue(capabilities.contains("milvus.search"));
        assertTrue(capabilities.contains("milvus.collection.list"));
    }

    @Test
    void shouldFallbackToStubWhenDatabaseAdapterFailsAndKeepAuditRecord() {
        McpCapabilityGateway stub = gatewayFrom("stub");
        DatabaseMcpAdapter adapter = new DatabaseMcpAdapter() {
            @Override
            public String namespace() {
                return "neo4j";
            }

            @Override
            public List<String> capabilities() {
                return List.of("neo4j.query");
            }

            @Override
            public Object invoke(String capability, Map<String, Object> params, Map<String, Object> context) {
                throw new McpGatewayException("MCP_TIMEOUT", true, "neo4j timeout");
            }
        };
        DatabaseMcpAdapterRouter router = new DatabaseMcpAdapterRouter(List.of(adapter));
        OpsAuditService opsAuditService = new OpsAuditService();
        McpGatewayService service = new McpGatewayService(
                stub,
                null,
                null,
                null,
                null,
                router,
                opsAuditService,
                1000,
                0,
                "auto",
                true
        );

        Map<String, Object> result = service.invoke("tester", "neo4j.query", Map.of("query", "MATCH (n) RETURN n"), Map.of("traceId", "trace-db-fallback"));

        assertEquals("fallback_stub", result.get("status"));
        assertEquals("MCP_TIMEOUT", result.get("errorCode"));
        assertTrue((Boolean) result.get("retryable"));
        assertEquals("stub", ((Map<?, ?>) result.get("result")).get("from"));
        List<OpsAuditService.OpsAuditRecord> recentRecords = opsAuditService.listRecent(1);
        assertFalse(recentRecords.isEmpty());
        assertEquals("MCP_INVOKE", recentRecords.get(0).action());
        assertEquals("trace-db-fallback", recentRecords.get(0).traceId());
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
