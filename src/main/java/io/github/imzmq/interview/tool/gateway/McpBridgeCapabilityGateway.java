package io.github.imzmq.interview.tool.gateway;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.beans.factory.annotation.Value;
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

@Component
@ConditionalOnProperty(prefix = "app.mcp.bridge", name = "enabled", havingValue = "true")
public class McpBridgeCapabilityGateway implements McpCapabilityGateway {
    private final RestClient restClient;
    private final String capabilitiesPath;
    private final String invokePath;

    public McpBridgeCapabilityGateway(
            RestClient.Builder restClientBuilder,
            @Value("${app.mcp.bridge.base-url:http://localhost:38080}") String baseUrl,
            @Value("${app.mcp.bridge.capabilities-path:/capabilities}") String capabilitiesPath,
            @Value("${app.mcp.bridge.invoke-path:/invoke}") String invokePath
    ) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.capabilitiesPath = capabilitiesPath;
        this.invokePath = invokePath;
    }

    @Override
    public List<String> listCapabilities() {
        McpGatewayException postError = null;
        try {
            return listCapabilitiesByJsonRpcPost();
        } catch (McpGatewayException ex) {
            postError = ex;
            if (!shouldFallbackToGet(ex)) {
                throw ex;
            }
        }
        try {
            return listCapabilitiesByGet();
        } catch (RestClientResponseException ex) {
            throw toHttpException("capabilities", ex.getStatusCode().value(), ex);
        } catch (ResourceAccessException ex) {
            throw toAccessException("mcp capabilities access failed", ex);
        } catch (RestClientException ex) {
            throw new McpGatewayException("MCP_REMOTE_ERROR", true, "mcp capabilities remote error", ex);
        } catch (McpGatewayException ex) {
            if (postError != null && shouldFallbackToGet(postError)) {
                throw ex;
            }
            throw ex;
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
        String requestId = requestIdOf(context, "invoke");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", requestId);
        request.put("method", normalizedName);
        request.put("params", normalizedParams);
        request.put("capability", normalizedName);
        try {
            RestClient.RequestBodySpec requestSpec = restClient.post().uri(invokePath);
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
            throw new McpGatewayException("MCP_INVALID_RESPONSE", false, "mcp invoke invalid response");
        } catch (McpGatewayException ex) {
            throw ex;
        } catch (RestClientResponseException ex) {
            throw toHttpException("invoke", ex.getStatusCode().value(), ex);
        } catch (ResourceAccessException ex) {
            throw toAccessException("mcp invoke access failed", ex);
        } catch (RestClientException ex) {
            throw new McpGatewayException("MCP_REMOTE_ERROR", true, "mcp invoke remote error", ex);
        }
    }

    private McpGatewayException toHttpException(String operation, int statusCode, Throwable cause) {
        String lowerOp = operation == null ? "invoke" : operation.trim().toLowerCase();
        String opText = "capabilities".equals(lowerOp) ? "mcp capabilities" : "mcp invoke";
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

    private boolean shouldFallbackToGet(McpGatewayException ex) {
        String code = ex.getErrorCode() == null ? "" : ex.getErrorCode().trim();
        return "MCP_REMOTE_404".equals(code)
                || "MCP_REMOTE_405".equals(code)
                || "MCP_REMOTE_501".equals(code)
                || "MCP_PROTOCOL_INCOMPATIBLE".equals(code);
    }

    private List<String> listCapabilitiesByJsonRpcPost() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", requestIdOf(Map.of(), "capabilities"));
        request.put("method", "tools/list");
        request.put("params", Map.of());
        try {
            Object body = restClient.post()
                    .uri(capabilitiesPath)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (requestSpec, response) -> {
                        throw toHttpException("capabilities", response.getStatusCode().value(), null);
                    })
                    .body(Object.class);
            return parseCapabilitiesBody(body);
        } catch (McpGatewayException ex) {
            throw ex;
        } catch (RestClientResponseException ex) {
            throw toHttpException("capabilities", ex.getStatusCode().value(), ex);
        } catch (ResourceAccessException ex) {
            throw toAccessException("mcp capabilities access failed", ex);
        } catch (RestClientException ex) {
            throw new McpGatewayException("MCP_REMOTE_ERROR", true, "mcp capabilities remote error", ex);
        }
    }

    private List<String> listCapabilitiesByGet() {
        Object body = restClient.get()
                .uri(capabilitiesPath)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (requestSpec, response) -> {
                    throw toHttpException("capabilities", response.getStatusCode().value(), null);
                })
                .body(Object.class);
        return parseCapabilitiesBody(body);
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
        throw new McpGatewayException("MCP_INVALID_RESPONSE", false, "mcp capabilities invalid response");
    }

    private List<String> parseCapabilitiesResult(Object resultBody) {
        if (resultBody instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(String::valueOf).toList();
        }
        if (resultBody instanceof Map<?, ?> resultMap) {
            Object capabilities = resultMap.get("capabilities");
            if (capabilities instanceof List<?> list) {
                return list.stream().filter(Objects::nonNull).map(String::valueOf).toList();
            }
        }
        throw new McpGatewayException("MCP_INVALID_RESPONSE", false, "mcp capabilities invalid response");
    }

    private Object parseInvokeResult(Object resultBody) {
        if (resultBody == null) {
            throw new McpGatewayException("MCP_INVALID_RESPONSE", false, "mcp invoke invalid response");
        }
        if (resultBody instanceof Map<?, ?> resultMap && resultMap.containsKey("result")) {
            Object nested = resultMap.get("result");
            if (nested == null) {
                throw new McpGatewayException("MCP_INVALID_RESPONSE", false, "mcp invoke invalid response");
            }
            return nested;
        }
        return resultBody;
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

    private McpGatewayException toAccessException(String message, ResourceAccessException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof SocketTimeoutException) {
            return new McpGatewayException("MCP_TIMEOUT", true, message, ex);
        }
        return new McpGatewayException("MCP_UNREACHABLE", true, message, ex);
    }
}

