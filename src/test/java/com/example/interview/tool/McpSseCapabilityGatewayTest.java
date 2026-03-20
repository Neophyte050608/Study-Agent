package com.example.interview.tool;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpSseCapabilityGatewayTest {

    @Test
    void shouldSendToolsListRequestEnvelope() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        RestClient restClient = mock(RestClient.class);
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(builder.baseUrl(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(restClient);
        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString(), any(Object[].class))).thenReturn(bodySpec);
        when(uriSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.body(any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(), any())).thenReturn(responseSpec);
        when(responseSpec.body(eq(Object.class))).thenReturn(Map.of("result", Map.of("tools", java.util.List.of(Map.of("name", "obsidian.write")))));
        McpSseCapabilityGateway gateway = new McpSseCapabilityGateway(builder, "http://localhost:38180", "/mcp");

        java.util.List<String> capabilities = gateway.listCapabilities();

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(bodySpec).body(captor.capture());
        Map<String, Object> payload = captor.getValue();
        assertEquals("2.0", payload.get("jsonrpc"));
        assertNotNull(payload.get("id"));
        assertEquals("tools/list", payload.get("method"));
        assertEquals(1, capabilities.size());
        assertEquals("obsidian.write", capabilities.get(0));
    }

    @Test
    void shouldSendToolsCallEnvelopeWithTraceId() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        RestClient restClient = mock(RestClient.class);
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(builder.baseUrl(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(restClient);
        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString(), any(Object[].class))).thenReturn(bodySpec);
        when(uriSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.body(any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(), any())).thenReturn(responseSpec);
        when(responseSpec.body(eq(Object.class))).thenReturn(Map.of("result", Map.of("structuredContent", Map.of("ok", true))));
        McpSseCapabilityGateway gateway = new McpSseCapabilityGateway(builder, "http://localhost:38180", "/mcp");

        Object result = gateway.invokeCapability("obsidian.write", Map.of("topic", "Java"), Map.of("traceId", "trace-sse-001"));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(bodySpec).body(captor.capture());
        verify(bodySpec).header("X-Trace-Id", "trace-sse-001");
        Map<String, Object> payload = captor.getValue();
        assertEquals("tools/call", payload.get("method"));
        assertEquals("invoke-trace-sse-001", payload.get("id"));
        assertTrue(payload.get("params") instanceof Map);
        assertTrue(result instanceof Map);
        assertEquals(true, ((Map<?, ?>) result).get("ok"));
    }

    @Test
    void shouldMapJsonRpcErrorToPermissionDenied() {
        McpSseCapabilityGateway gateway = gatewayWithBody(Map.of(
                "error", Map.of("code", "PERMISSION_DENIED", "message", "forbidden")
        ));

        McpGatewayException ex = assertThrows(McpGatewayException.class, gateway::listCapabilities);

        assertEquals("MCP_PERMISSION_DENIED", ex.getErrorCode());
        assertFalse(ex.isRetryable());
    }

    @Test
    void shouldMapTimeoutToRetryableTimeout() {
        McpSseCapabilityGateway gateway = gatewayWithError(
                new ResourceAccessException("timeout", new SocketTimeoutException("read timed out"))
        );

        McpGatewayException ex = assertThrows(McpGatewayException.class, () -> gateway.invokeCapability("obsidian.write", Map.of()));

        assertEquals("MCP_TIMEOUT", ex.getErrorCode());
        assertTrue(ex.isRetryable());
    }

    @Test
    void shouldMapBadRequestToInvalidParams() {
        McpSseCapabilityGateway gateway = gatewayWithError(
                HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "bad request", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8)
        );

        McpGatewayException ex = assertThrows(McpGatewayException.class, gateway::listCapabilities);

        assertEquals("MCP_INVALID_PARAMS", ex.getErrorCode());
        assertFalse(ex.isRetryable());
    }

    @Test
    void shouldMapConnectFailureToUnreachable() {
        McpSseCapabilityGateway gateway = gatewayWithError(
                new ResourceAccessException("connect failed", new ConnectException("connection refused"))
        );

        McpGatewayException ex = assertThrows(McpGatewayException.class, () -> gateway.invokeCapability("code.execute", Map.of()));

        assertEquals("MCP_UNREACHABLE", ex.getErrorCode());
        assertTrue(ex.isRetryable());
    }

    private McpSseCapabilityGateway gatewayWithBody(Object body) {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        RestClient restClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        when(builder.baseUrl(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(restClient);
        when(restClient.post().uri(anyString(), any(Object[].class)).body(any(Object.class)).retrieve().onStatus(any(), any()).body(eq(Object.class))).thenReturn(body);
        return new McpSseCapabilityGateway(builder, "http://localhost:38180", "/mcp");
    }

    private McpSseCapabilityGateway gatewayWithError(RuntimeException error) {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        RestClient restClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        when(builder.baseUrl(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(restClient);
        when(restClient.post().uri(anyString(), any(Object[].class)).body(any(Object.class)).retrieve().onStatus(any(), any()).body(eq(Object.class))).thenThrow(error);
        return new McpSseCapabilityGateway(builder, "http://localhost:38180", "/mcp");
    }
}
