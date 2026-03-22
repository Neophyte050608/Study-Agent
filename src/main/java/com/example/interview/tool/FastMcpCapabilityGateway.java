package com.example.interview.tool;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * fastMCP4J 网关实现。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>对接 fastMCP4J 的 MCP JSON-RPC 端点，提供能力发现与调用。</li>
 *   <li>把 HTTP、协议、超时异常统一映射为 McpGatewayException，便于上层重试与降级。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "app.mcp.fastmcp", name = "enabled", havingValue = "true")
public class FastMcpCapabilityGateway implements McpCapabilityGateway {
    private final RestClient restClient;
    private final String endpointPath;
    private final String transport;

    public FastMcpCapabilityGateway(
            RestClient.Builder restClientBuilder,
            @Value("${app.mcp.fastmcp.base-url:http://localhost:38190}") String baseUrl,
            @Value("${app.mcp.fastmcp.endpoint-path:/mcp}") String endpointPath,
            @Value("${app.mcp.fastmcp.transport:streamable}") String transport
    ) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.endpointPath = endpointPath;
        this.transport = transport == null ? "streamable" : transport.trim().toLowerCase();
    }

    @Override
    public List<String> listCapabilities() {
        validateTransport();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", requestIdOf(Map.of(), "capabilities"));
        request.put("method", "tools/list");
        request.put("params", Map.of());
        try {
            Object body = restClient.post()
                    .uri(endpointPath)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, response) -> {
                        throw toHttpException("capabilities", response.getStatusCode().value(), null);
                    })
                    .body(Object.class);
            return parseCapabilitiesBody(body);
        } catch (McpGatewayException ex) {
            throw ex;
        } catch (RestClientResponseException ex) {
            throw toHttpException("capabilities", ex.getStatusCode().value(), ex);
        } catch (ResourceAccessException ex) {
            throw toAccessException("mcp fastmcp capabilities access failed", ex);
        } catch (RestClientException ex) {
            throw new McpGatewayException("MCP_REMOTE_ERROR", true, "mcp fastmcp capabilities remote error", ex);
        }
    }

    @Override
    public Object invokeCapability(String name, Map<String, Object> params) {
        return invokeCapability(name, params, Map.of());
    }

    @Override
    public Object invokeCapability(String name, Map<String, Object> params, Map<String, Object> context) {
        validateTransport();
        String normalizedName = name == null ? "" : name.trim();
        Map<String, Object> normalizedParams = params == null ? Map.of() : params;
        String traceId = traceIdOf(context);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", requestIdOf(context, "invoke"));
        request.put("method", "tools/call");
        request.put("params", Map.of("name", normalizedName, "arguments", normalizedParams));
        try {
            RestClient.RequestBodySpec requestSpec = restClient.post().uri(endpointPath);
            if (!traceId.isBlank()) {
                requestSpec = requestSpec.header("X-Trace-Id", traceId);
            }
            Object body = requestSpec
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, response) -> {
                        throw toHttpException("invoke", response.getStatusCode().value(), null);
                    })
                    .body(Object.class);
            return parseInvokeBody(body);
        } catch (McpGatewayException ex) {
            throw ex;
        } catch (RestClientResponseException ex) {
            throw toHttpException("invoke", ex.getStatusCode().value(), ex);
        } catch (ResourceAccessException ex) {
            throw toAccessException("mcp fastmcp invoke access failed", ex);
        } catch (RestClientException ex) {
            throw new McpGatewayException("MCP_REMOTE_ERROR", true, "mcp fastmcp invoke remote error", ex);
        }
    }

    private void validateTransport() {
        if ("streamable".equals(transport) || "sse".equals(transport)) {
            return;
        }
        throw new McpGatewayException("MCP_PROTOCOL_INCOMPATIBLE", false, "mcp fastmcp transport is not supported");
    }

    private Object parseInvokeBody(Object body) {
        if (body instanceof Map<?, ?> map) {
            if (map.containsKey("error")) {
                throw toRemoteProtocolException(map.get("error"));
            }
            if (map.containsKey("result")) {
                return parseInvokeResult(map.get("result"));
            }
        }
        if (body != null) {
            return body;
        }
        throw new McpGatewayException("MCP_INVALID_RESPONSE", false, "mcp fastmcp invoke invalid response");
    }

    private List<String> parseCapabilitiesBody(Object body) {
        if (body instanceof Map<?, ?> map) {
            if (map.containsKey("error")) {
                throw toRemoteProtocolException(map.get("error"));
            }
            if (map.containsKey("result")) {
                return parseCapabilitiesResult(map.get("result"));
            }
            if (map.containsKey("capabilities")) {
                return parseCapabilitiesResult(map);
            }
        }
        if (body instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(String::valueOf).toList();
        }
        throw new McpGatewayException("MCP_INVALID_RESPONSE", false, "mcp fastmcp capabilities invalid response");
    }

    private List<String> parseCapabilitiesResult(Object resultBody) {
        if (resultBody instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(String::valueOf).toList();
        }
        if (resultBody instanceof Map<?, ?> map) {
            Object tools = map.get("tools");
            if (tools instanceof List<?> list) {
                return list.stream()
                        .map(this::toolNameOf)
                        .filter(name -> name != null && !name.isBlank())
                        .toList();
            }
            Object capabilities = map.get("capabilities");
            if (capabilities instanceof List<?> list) {
                return list.stream().filter(Objects::nonNull).map(String::valueOf).toList();
            }
        }
        throw new McpGatewayException("MCP_INVALID_RESPONSE", false, "mcp fastmcp capabilities invalid response");
    }

    private Object parseInvokeResult(Object resultBody) {
        if (resultBody == null) {
            throw new McpGatewayException("MCP_INVALID_RESPONSE", false, "mcp fastmcp invoke invalid response");
        }
        if (resultBody instanceof Map<?, ?> map) {
            if (map.containsKey("structuredContent")) {
                return map.get("structuredContent");
            }
            if (map.containsKey("result")) {
                Object nested = map.get("result");
                if (nested == null) {
                    throw new McpGatewayException("MCP_INVALID_RESPONSE", false, "mcp fastmcp invoke invalid response");
                }
                return nested;
            }
        }
        return resultBody;
    }

    private String toolNameOf(Object rawTool) {
        if (rawTool instanceof Map<?, ?> toolMap) {
            Object rawName = toolMap.get("name");
            return rawName == null ? "" : String.valueOf(rawName).trim();
        }
        if (rawTool == null) {
            return "";
        }
        return String.valueOf(rawTool).trim();
    }

    private String requestIdOf(Map<String, Object> context, String prefix) {
        String traceId = traceIdOf(context);
        if (!traceId.isBlank()) {
            return prefix + "-" + traceId;
        }
        return prefix + "-" + System.nanoTime();
    }

    private String traceIdOf(Map<String, Object> context) {
        if (context == null) {
            return "";
        }
        Object rawTraceId = context.get("traceId");
        if (rawTraceId == null) {
            return "";
        }
        return String.valueOf(rawTraceId).trim();
    }

    private McpGatewayException toRemoteProtocolException(Object errorBody) {
        String code = "";
        String message = "mcp remote error";
        if (errorBody instanceof Map<?, ?> map) {
            Object rawCode = map.get("code");
            if (rawCode != null) {
                code = String.valueOf(rawCode).trim();
            }
            Object rawMessage = map.get("message");
            if (rawMessage != null && !String.valueOf(rawMessage).isBlank()) {
                message = String.valueOf(rawMessage).trim();
            }
        }
        if ("-32602".equals(code) || "INVALID_PARAMS".equalsIgnoreCase(code)) {
            return new McpGatewayException("MCP_INVALID_PARAMS", false, message);
        }
        if ("-32001".equals(code) || "PERMISSION_DENIED".equalsIgnoreCase(code) || "UNAUTHORIZED".equalsIgnoreCase(code)) {
            return new McpGatewayException("MCP_PERMISSION_DENIED", false, message);
        }
        if ("-32601".equals(code) || "METHOD_NOT_FOUND".equalsIgnoreCase(code) || "PROTOCOL_INCOMPATIBLE".equalsIgnoreCase(code)) {
            return new McpGatewayException("MCP_PROTOCOL_INCOMPATIBLE", false, message);
        }
        return new McpGatewayException("MCP_REMOTE_ERROR", true, message);
    }

    private McpGatewayException toHttpException(String operation, int statusCode, Throwable cause) {
        String lowerOp = operation == null ? "invoke" : operation.trim().toLowerCase();
        String opText = "capabilities".equals(lowerOp) ? "mcp fastmcp capabilities" : "mcp fastmcp invoke";
        if (statusCode == 400 || statusCode == 422) {
            return new McpGatewayException("MCP_INVALID_PARAMS", false, opText + " invalid params", cause);
        }
        if (statusCode == 401 || statusCode == 403) {
            return new McpGatewayException("MCP_PERMISSION_DENIED", false, opText + " permission denied", cause);
        }
        if (statusCode == 406 || statusCode == 409 || statusCode == 412 || statusCode == 415 || statusCode == 426) {
            return new McpGatewayException("MCP_PROTOCOL_INCOMPATIBLE", false, opText + " protocol incompatible", cause);
        }
        return new McpGatewayException("MCP_REMOTE_" + statusCode, statusCode >= 500, opText + " remote error", cause);
    }

    private McpGatewayException toAccessException(String message, ResourceAccessException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof SocketTimeoutException) {
            return new McpGatewayException("MCP_TIMEOUT", true, message, ex);
        }
        return new McpGatewayException("MCP_UNREACHABLE", true, message, ex);
    }
}
