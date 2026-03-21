package com.example.interview.tool;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
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
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class McpBridgeCapabilityGatewayTest {

    @Test
    void shouldSendJsonRpcCapabilitiesEnvelope() {
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
        when(responseSpec.body(eq(Object.class))).thenReturn(Map.of("result", Map.of("capabilities", java.util.List.of("obsidian.write"))));
        McpBridgeCapabilityGateway gateway = new McpBridgeCapabilityGateway(builder, "http://localhost:38080", "/capabilities", "/invoke");

        java.util.List<String> capabilities = gateway.listCapabilities();

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(bodySpec).body(captor.capture());
        Map<String, Object> payload = captor.getValue();
        assertEquals("2.0", payload.get("jsonrpc"));
        assertNotNull(payload.get("id"));
        assertEquals("tools/list", payload.get("method"));
        assertTrue(payload.get("params") instanceof Map);
        assertEquals(1, capabilities.size());
    }

    @Test
    void shouldReadCapabilitiesFromJsonRpcResult() {
        McpBridgeCapabilityGateway gateway = gatewayWithListBody(Map.of(
                "result", Map.of("capabilities", java.util.List.of("obsidian.write", "code.execute"))
        ));

        java.util.List<String> capabilities = gateway.listCapabilities();

        assertEquals(2, capabilities.size());
        assertEquals("obsidian.write", capabilities.get(0));
    }

    @Test
    void shouldMapBadRequestToInvalidParams() {
        McpBridgeCapabilityGateway gateway = gatewayWithListError(
                HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "bad request", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8)
        );

        McpGatewayException ex = assertThrows(McpGatewayException.class, gateway::listCapabilities);

        assertEquals("MCP_INVALID_PARAMS", ex.getErrorCode());
        assertFalse(ex.isRetryable());
    }

    @Test
    void shouldMapUnprocessableEntityToInvalidParams() {
        McpBridgeCapabilityGateway gateway = gatewayWithInvokeError(
                HttpClientErrorException.create(HttpStatus.UNPROCESSABLE_ENTITY, "unprocessable", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8)
        );

        McpGatewayException ex = assertThrows(McpGatewayException.class, () -> gateway.invokeCapability("obsidian.write", Map.of()));

        assertEquals("MCP_INVALID_PARAMS", ex.getErrorCode());
        assertFalse(ex.isRetryable());
    }

    @Test
    void shouldMapForbiddenToPermissionDenied() {
        McpBridgeCapabilityGateway gateway = gatewayWithInvokeError(
                HttpClientErrorException.create(HttpStatus.FORBIDDEN, "forbidden", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8)
        );

        McpGatewayException ex = assertThrows(McpGatewayException.class, () -> gateway.invokeCapability("obsidian.write", Map.of()));

        assertEquals("MCP_PERMISSION_DENIED", ex.getErrorCode());
        assertFalse(ex.isRetryable());
    }

    @Test
    void shouldMapUnauthorizedToPermissionDenied() {
        McpBridgeCapabilityGateway gateway = gatewayWithListError(
                HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "unauthorized", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8)
        );

        McpGatewayException ex = assertThrows(McpGatewayException.class, gateway::listCapabilities);

        assertEquals("MCP_PERMISSION_DENIED", ex.getErrorCode());
        assertFalse(ex.isRetryable());
    }

    @Test
    void shouldMapUnsupportedMediaTypeToProtocolIncompatible() {
        McpBridgeCapabilityGateway gateway = gatewayWithInvokeError(
                HttpClientErrorException.create(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8)
        );

        McpGatewayException ex = assertThrows(McpGatewayException.class, () -> gateway.invokeCapability("code.execute", Map.of("language", "java")));

        assertEquals("MCP_PROTOCOL_INCOMPATIBLE", ex.getErrorCode());
        assertFalse(ex.isRetryable());
    }

    @Test
    void shouldMapUpgradeRequiredToProtocolIncompatible() {
        McpBridgeCapabilityGateway gateway = gatewayWithListError(
                HttpClientErrorException.create(HttpStatus.UPGRADE_REQUIRED, "upgrade", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8)
        );

        McpGatewayException ex = assertThrows(McpGatewayException.class, gateway::listCapabilities);

        assertEquals("MCP_PROTOCOL_INCOMPATIBLE", ex.getErrorCode());
        assertFalse(ex.isRetryable());
    }

    @Test
    void shouldMapServerErrorToRetryableRemoteError() {
        McpBridgeCapabilityGateway gateway = gatewayWithListError(
                HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "bad gateway", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8)
        );

        McpGatewayException ex = assertThrows(McpGatewayException.class, gateway::listCapabilities);

        assertEquals("MCP_REMOTE_502", ex.getErrorCode());
        assertTrue(ex.isRetryable());
    }

    @Test
    void shouldMapSocketTimeoutToTimeout() {
        McpBridgeCapabilityGateway gateway = gatewayWithInvokeError(
                new ResourceAccessException("timeout", new SocketTimeoutException("read timed out"))
        );

        McpGatewayException ex = assertThrows(McpGatewayException.class, () -> gateway.invokeCapability("obsidian.write", Map.of()));

        assertEquals("MCP_TIMEOUT", ex.getErrorCode());
        assertTrue(ex.isRetryable());
    }

    @Test
    void shouldMapAccessFailureToUnreachable() {
        McpBridgeCapabilityGateway gateway = gatewayWithListError(
                new ResourceAccessException("connect failed", new ConnectException("connection refused"))
        );

        McpGatewayException ex = assertThrows(McpGatewayException.class, gateway::listCapabilities);

        assertEquals("MCP_UNREACHABLE", ex.getErrorCode());
        assertTrue(ex.isRetryable());
    }

    @Test
    void shouldFailWhenCapabilitiesResponseIsInvalid() {
        McpBridgeCapabilityGateway gateway = gatewayWithListBody(Map.of("items", java.util.List.of("obsidian.write")));

        McpGatewayException ex = assertThrows(McpGatewayException.class, gateway::listCapabilities);

        assertEquals("MCP_INVALID_RESPONSE", ex.getErrorCode());
        assertFalse(ex.isRetryable());
    }

    @Test
    void shouldMapJsonRpcCapabilitiesErrorToPermissionDenied() {
        McpBridgeCapabilityGateway gateway = gatewayWithListBody(Map.of(
                "error", Map.of("code", "PERMISSION_DENIED", "message", "forbidden")
        ));

        McpGatewayException ex = assertThrows(McpGatewayException.class, gateway::listCapabilities);

        assertEquals("MCP_PERMISSION_DENIED", ex.getErrorCode());
        assertFalse(ex.isRetryable());
    }

    @Test
    void shouldFallbackToGetWhenPostNotSupported() {
        McpBridgeCapabilityGateway gateway = gatewayWithPostErrorAndGetBody(
                HttpClientErrorException.create(HttpStatus.METHOD_NOT_ALLOWED, "method not allowed", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8),
                Map.of("capabilities", java.util.List.of("obsidian.write", "code.execute"))
        );

        java.util.List<String> capabilities = gateway.listCapabilities();

        assertEquals(2, capabilities.size());
        assertEquals("obsidian.write", capabilities.get(0));
    }

    @Test
    void shouldFailWhenInvokeResponseIsNull() {
        McpBridgeCapabilityGateway gateway = gatewayWithInvokeBody(null);

        McpGatewayException ex = assertThrows(McpGatewayException.class, () -> gateway.invokeCapability("obsidian.write", Map.of()));

        assertEquals("MCP_INVALID_RESPONSE", ex.getErrorCode());
        assertFalse(ex.isRetryable());
    }

    @Test
    void shouldMapJsonRpcInvalidParamsErrorToInvalidParams() {
        McpBridgeCapabilityGateway gateway = gatewayWithInvokeBody(Map.of(
                "error", Map.of("code", -32602, "message", "invalid params")
        ));

        McpGatewayException ex = assertThrows(McpGatewayException.class, () -> gateway.invokeCapability("obsidian.write", Map.of("topic", "")));

        assertEquals("MCP_INVALID_PARAMS", ex.getErrorCode());
        assertFalse(ex.isRetryable());
    }

    @Test
    void shouldMapJsonRpcPermissionErrorToPermissionDenied() {
        McpBridgeCapabilityGateway gateway = gatewayWithInvokeBody(Map.of(
                "error", Map.of("code", "PERMISSION_DENIED", "message", "forbidden")
        ));

        McpGatewayException ex = assertThrows(McpGatewayException.class, () -> gateway.invokeCapability("obsidian.write", Map.of()));

        assertEquals("MCP_PERMISSION_DENIED", ex.getErrorCode());
        assertFalse(ex.isRetryable());
    }

    @Test
    void shouldMapJsonRpcMethodNotFoundToProtocolIncompatible() {
        McpBridgeCapabilityGateway gateway = gatewayWithInvokeBody(Map.of(
                "error", Map.of("code", -32601, "message", "method not found")
        ));

        McpGatewayException ex = assertThrows(McpGatewayException.class, () -> gateway.invokeCapability("obsidian.write", Map.of()));

        assertEquals("MCP_PROTOCOL_INCOMPATIBLE", ex.getErrorCode());
        assertFalse(ex.isRetryable());
    }

    @Test
    void shouldReadNestedInvokeResultInJsonRpcEnvelope() {
        McpBridgeCapabilityGateway gateway = gatewayWithInvokeBody(Map.of(
                "result", Map.of("result", Map.of("notePath", "notes/a.md"))
        ));

        Object result = gateway.invokeCapability("obsidian.write", Map.of());

        assertTrue(result instanceof Map);
        assertEquals("notes/a.md", ((Map<?, ?>) result).get("notePath"));
    }

    @Test
    void shouldSendJsonRpcInvokeEnvelope() {
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
        when(responseSpec.body(eq(Object.class))).thenReturn(Map.of("result", Map.of("ok", true)));
        McpBridgeCapabilityGateway gateway = new McpBridgeCapabilityGateway(builder, "http://localhost:38080", "/capabilities", "/invoke");

        gateway.invokeCapability("obsidian.write", Map.of("topic", "Java并发"));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(bodySpec).body(captor.capture());
        Map<String, Object> payload = captor.getValue();
        assertEquals("2.0", payload.get("jsonrpc"));
        assertNotNull(payload.get("id"));
        assertEquals("obsidian.write", payload.get("method"));
        assertEquals("obsidian.write", payload.get("capability"));
        assertTrue(payload.get("params") instanceof Map);
    }

    @Test
    void shouldUseTraceIdAsInvokeRequestId() {
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
        when(responseSpec.body(eq(Object.class))).thenReturn(Map.of("result", Map.of("ok", true)));
        McpBridgeCapabilityGateway gateway = new McpBridgeCapabilityGateway(builder, "http://localhost:38080", "/capabilities", "/invoke");

        gateway.invokeCapability("obsidian.write", Map.of(), Map.of("traceId", "trace-mcp-001"));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(bodySpec).body(captor.capture());
        verify(bodySpec).header("X-Trace-Id", "trace-mcp-001");
        Map<String, Object> payload = captor.getValue();
        assertEquals("invoke-trace-mcp-001", payload.get("id"));
    }

    private McpBridgeCapabilityGateway gatewayWithListError(RuntimeException error) {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        RestClient restClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        when(builder.baseUrl(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(restClient);
        when(restClient.post().uri(anyString(), any(Object[].class)).body(any(Object.class)).retrieve().onStatus(any(), any()).body(eq(Object.class))).thenThrow(error);
        when(restClient.get().uri(anyString(), any(Object[].class)).retrieve().onStatus(any(), any()).body(eq(Object.class))).thenThrow(error);
        return new McpBridgeCapabilityGateway(builder, "http://localhost:38080", "/capabilities", "/invoke");
    }

    private McpBridgeCapabilityGateway gatewayWithInvokeError(RuntimeException error) {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        RestClient restClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        when(builder.baseUrl(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(restClient);
        when(restClient.post().uri(anyString(), any(Object[].class)).body(any(Object.class)).retrieve().onStatus(any(), any()).body(eq(Object.class))).thenThrow(error);
        return new McpBridgeCapabilityGateway(builder, "http://localhost:38080", "/capabilities", "/invoke");
    }

    private McpBridgeCapabilityGateway gatewayWithListBody(Object body) {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        RestClient restClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        when(builder.baseUrl(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(restClient);
        when(restClient.post().uri(anyString(), any(Object[].class)).body(any(Object.class)).retrieve().onStatus(any(), any()).body(eq(Object.class))).thenReturn(body);
        when(restClient.get().uri(anyString(), any(Object[].class)).retrieve().onStatus(any(), any()).body(eq(Object.class))).thenReturn(body);
        return new McpBridgeCapabilityGateway(builder, "http://localhost:38080", "/capabilities", "/invoke");
    }

    private McpBridgeCapabilityGateway gatewayWithPostErrorAndGetBody(RuntimeException postError, Object getBody) {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        RestClient restClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        when(builder.baseUrl(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(restClient);
        when(restClient.post().uri(anyString(), any(Object[].class)).body(any(Object.class)).retrieve().onStatus(any(), any()).body(eq(Object.class))).thenThrow(postError);
        when(restClient.get().uri(anyString(), any(Object[].class)).retrieve().onStatus(any(), any()).body(eq(Object.class))).thenReturn(getBody);
        return new McpBridgeCapabilityGateway(builder, "http://localhost:38080", "/capabilities", "/invoke");
    }

    private McpBridgeCapabilityGateway gatewayWithInvokeBody(Object body) {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        RestClient restClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        when(builder.baseUrl(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(restClient);
        when(restClient.post().uri(anyString(), any(Object[].class)).body(any(Object.class)).retrieve().onStatus(any(), any()).body(eq(Object.class))).thenReturn(body);
        return new McpBridgeCapabilityGateway(builder, "http://localhost:38080", "/capabilities", "/invoke");
    }
}
