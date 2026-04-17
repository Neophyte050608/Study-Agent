package io.github.imzmq.interview.tool.gateway;

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
 * MCP SSE (Server-Sent Events) 网关实现。
 * 
 * 职责：
 * 1. 标准协议支持：遵循 MCP 官方的 SSE 接入规范进行工具发现和调用。
 * 2. 异步调用封装：通过 RestClient 模拟 SSE 链路中的指令发送（tools/call）。
 */
@Component
@ConditionalOnProperty(prefix = "app.mcp.sse", name = "enabled", havingValue = "true")
public class McpSseCapabilityGateway implements McpCapabilityGateway {
    private final RestClient restClient;
    private final String endpointPath;

    public McpSseCapabilityGateway(
            RestClient.Builder restClientBuilder,
            @Value("${app.mcp.sse.base-url:http://localhost:38180}") String baseUrl,
            @Value("${app.mcp.sse.endpoint-path:/mcp}") String endpointPath
    ) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.endpointPath = endpointPath;
    }

    @Override
    public List<String> listCapabilities() {
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
            throw toAccessException("mcp sse capabilities access failed", ex);
        } catch (RestClientException ex) {
            throw new McpGatewayException("MCP_REMOTE_ERROR", true, "mcp sse capabilities remote error", ex);
        }
    }

    @Override
    public Object invokeCapability(String name, Map<String, Object> params) {
        return invokeCapability(name, params, Map.of());
    }

    @Override
    public Object invokeCapability(String name, Map<String, Object> params, Map<String, Object> context) {
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
            throw toAccessException("mcp sse invoke access failed", ex);
        } catch (RestClientException ex) {
            throw new McpGatewayException("MCP_REMOTE_ERROR", true, "mcp sse invoke remote error", ex);
        }
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
        throw new McpGatewayException("MCP_INVALID_RESPONSE", false, "mcp sse invoke invalid response");
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
        throw new McpGatewayException("MCP_INVALID_RESPONSE", false, "mcp sse capabilities invalid response");
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
        throw new McpGatewayException("MCP_INVALID_RESPONSE", false, "mcp sse capabilities invalid response");
    }

    private Object parseInvokeResult(Object resultBody) {
        if (resultBody == null) {
            throw new McpGatewayException("MCP_INVALID_RESPONSE", false, "mcp sse invoke invalid response");
        }
        if (resultBody instanceof Map<?, ?> map) {
            if (map.containsKey("structuredContent")) {
                return map.get("structuredContent");
            }
            if (map.containsKey("result")) {
                Object nested = map.get("result");
                if (nested == null) {
                    throw new McpGatewayException("MCP_INVALID_RESPONSE", false, "mcp sse invoke invalid response");
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
        String opText = "capabilities".equals(lowerOp) ? "mcp sse capabilities" : "mcp sse invoke";
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

