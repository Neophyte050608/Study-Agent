package com.example.interview.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpStdioCapabilityGatewayTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldSendToolsListRequestAndParseTools() {
        FakeProcess process = FakeProcess.finished("{\"result\":{\"tools\":[{\"name\":\"obsidian.write\"}]}}");
        McpStdioCapabilityGateway gateway = new McpStdioCapabilityGateway(
                objectMapper,
                "mcp-server",
                "--stdio",
                1200,
                command -> process
        );

        List<String> capabilities = gateway.listCapabilities();

        assertEquals(1, capabilities.size());
        assertEquals("obsidian.write", capabilities.get(0));
        assertTrue(process.stdinText().contains("\"method\":\"tools/list\""));
    }

    @Test
    void shouldSendToolsCallRequestWithTraceId() {
        FakeProcess process = FakeProcess.finished("{\"result\":{\"structuredContent\":{\"ok\":true}}}");
        McpStdioCapabilityGateway gateway = new McpStdioCapabilityGateway(
                objectMapper,
                "mcp-server",
                "",
                1200,
                command -> process
        );

        Object result = gateway.invokeCapability("obsidian.write", Map.of("topic", "Java"), Map.of("traceId", "trace-stdio-1"));

        assertTrue(result instanceof Map);
        assertEquals(true, ((Map<?, ?>) result).get("ok"));
        assertTrue(process.stdinText().contains("\"method\":\"tools/call\""));
        assertTrue(process.stdinText().contains("\"id\":\"invoke-trace-stdio-1\""));
    }

    @Test
    void shouldMapJsonRpcPermissionErrorToNonRetryable() {
        FakeProcess process = FakeProcess.finished("{\"error\":{\"code\":\"PERMISSION_DENIED\",\"message\":\"forbidden\"}}");
        McpStdioCapabilityGateway gateway = new McpStdioCapabilityGateway(
                objectMapper,
                "mcp-server",
                "",
                1200,
                command -> process
        );

        McpGatewayException ex = assertThrows(McpGatewayException.class, gateway::listCapabilities);

        assertEquals("MCP_PERMISSION_DENIED", ex.getErrorCode());
        assertFalse(ex.isRetryable());
    }

    @Test
    void shouldMapTimeoutWhenProcessNotFinished() {
        FakeProcess process = FakeProcess.timeout();
        McpStdioCapabilityGateway gateway = new McpStdioCapabilityGateway(
                objectMapper,
                "mcp-server",
                "",
                300,
                command -> process
        );

        McpGatewayException ex = assertThrows(McpGatewayException.class, gateway::listCapabilities);

        assertEquals("MCP_TIMEOUT", ex.getErrorCode());
        assertTrue(ex.isRetryable());
    }

    @Test
    void shouldMapUnreachableWhenProcessStartFails() {
        McpStdioCapabilityGateway gateway = new McpStdioCapabilityGateway(
                objectMapper,
                "mcp-server",
                "",
                300,
                command -> {
                    throw new java.io.IOException("boom");
                }
        );

        McpGatewayException ex = assertThrows(McpGatewayException.class, gateway::listCapabilities);

        assertEquals("MCP_UNREACHABLE", ex.getErrorCode());
        assertTrue(ex.isRetryable());
    }

    static class FakeProcess extends Process {
        private final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        private final ByteArrayInputStream stdout;
        private final ByteArrayInputStream stderr;
        private final boolean finished;

        private FakeProcess(String stdout, String stderr, boolean finished) {
            this.stdout = new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8));
            this.stderr = new ByteArrayInputStream(stderr.getBytes(StandardCharsets.UTF_8));
            this.finished = finished;
        }

        static FakeProcess finished(String stdout) {
            return new FakeProcess(stdout, "", true);
        }

        static FakeProcess timeout() {
            return new FakeProcess("", "", false);
        }

        String stdinText() {
            return stdin.toString(StandardCharsets.UTF_8);
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return stderr;
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return finished;
        }

        @Override
        public int exitValue() {
            return finished ? 0 : 1;
        }

        @Override
        public void destroy() {
        }

        @Override
        public Process destroyForcibly() {
            return this;
        }

        @Override
        public boolean isAlive() {
            return !finished;
        }
    }
}
