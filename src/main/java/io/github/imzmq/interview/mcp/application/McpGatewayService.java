package io.github.imzmq.interview.mcp.application;

import io.github.imzmq.interview.tool.adapter.DatabaseMcpAdapterRouter;
import io.github.imzmq.interview.tool.gateway.FastMcpCapabilityGateway;
import io.github.imzmq.interview.tool.gateway.McpBridgeCapabilityGateway;
import io.github.imzmq.interview.tool.gateway.McpCapabilityGateway;
import io.github.imzmq.interview.common.api.BusinessException;
import io.github.imzmq.interview.common.api.ErrorCode;

import io.github.imzmq.interview.tool.gateway.McpSseCapabilityGateway;
import io.github.imzmq.interview.tool.gateway.McpStdioCapabilityGateway;
import io.github.imzmq.interview.tool.gateway.StubMcpCapabilityGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class McpGatewayService {

    private final McpCapabilityGateway stubGateway;
    private final GatewaySelectionStrategy gatewaySelectionStrategy;
    private final OpsAuditService opsAuditService;
    private final int timeoutMillis;
    private final int retries;
    private final String mode;
    private final boolean fallbackToStub;

    private static final int MAX_READ_LIMIT = 2000;
    private static final Set<String> FILE_READ_CAPABILITIES = Set.of(
            "obsidian.read",
            "file.read",
            "filesystem.read",
            "mcp.file.read"
    );

    @Autowired
    public McpGatewayService(
            StubMcpCapabilityGateway stubGateway,
            @Autowired(required = false) McpBridgeCapabilityGateway bridgeGateway,
            @Autowired(required = false) McpSseCapabilityGateway sseGateway,
            @Autowired(required = false) McpStdioCapabilityGateway stdioGateway,
            @Autowired(required = false) FastMcpCapabilityGateway fastMcpGateway,
            @Autowired(required = false) DatabaseMcpAdapterRouter databaseMcpAdapterRouter,
            GatewaySelectionStrategy gatewaySelectionStrategy,
            OpsAuditService opsAuditService,
            @Value("${app.mcp.timeout-millis:1500}") int timeoutMillis,
            @Value("${app.mcp.retries:2}") int retries,
            @Value("${app.mcp.mode:stub}") String mode,
            @Value("${app.mcp.fallback-to-stub:true}") boolean fallbackToStub
    ) {
        this.stubGateway = stubGateway;
        this.gatewaySelectionStrategy = gatewaySelectionStrategy != null
                ? gatewaySelectionStrategy
                : new GatewaySelectionStrategy(stubGateway, bridgeGateway, sseGateway, stdioGateway, fastMcpGateway, databaseMcpAdapterRouter);
        this.opsAuditService = opsAuditService;
        this.timeoutMillis = Math.max(200, timeoutMillis);
        this.retries = Math.max(0, retries);
        this.mode = mode == null ? "stub" : mode.trim().toLowerCase();
        this.fallbackToStub = fallbackToStub;
    }

    public List<String> discoverCapabilities(String operator) {
        return discoverCapabilities(operator, "");
    }

    public List<String> discoverCapabilities(String operator, String traceId) {
        McpCapabilityGateway gateway;
        try {
            gateway = gatewaySelectionStrategy.primaryGateway(mode);
        } catch (RuntimeException ex) {
            if (fallbackToStub) {
                List<String> capabilities = mergeCapabilities(stubGateway.listCapabilities(), gatewaySelectionStrategy.databaseCapabilities());
                record(
                        operator,
                        "MCP_DISCOVER_CAPABILITIES",
                        Map.of("count", capabilities == null ? 0 : capabilities.size()),
                        "fallback_stub",
                        errorCodeOf(ex),
                        retryableOf(ex),
                        false,
                        "fallback to stub",
                        traceId
                );
                return capabilities == null ? List.of() : capabilities;
            }
            record(
                    operator,
                    "MCP_DISCOVER_CAPABILITIES",
                    Map.of("count", 0),
                    "error",
                    errorCodeOf(ex),
                    retryableOf(ex),
                    false,
                    errorMessageOf(ex),
                    traceId
            );
            throw ex;
        }

        List<String> capabilities;
        boolean success = true;
        String message = "ok";
        try {
            capabilities = mergeCapabilities(gateway.listCapabilities(), gatewaySelectionStrategy.databaseCapabilities());
        } catch (RuntimeException ex) {
            if (shouldFallback(gateway)) {
                capabilities = mergeCapabilities(stubGateway.listCapabilities(), gatewaySelectionStrategy.databaseCapabilities());
                success = false;
                message = "fallback to stub";
            } else {
                record(
                        operator,
                        "MCP_DISCOVER_CAPABILITIES",
                        Map.of("count", 0),
                        "error",
                        errorCodeOf(ex),
                        retryableOf(ex),
                        false,
                        errorMessageOf(ex),
                        traceId
                );
                throw ex;
            }
        }

        record(
                operator,
                "MCP_DISCOVER_CAPABILITIES",
                Map.of("count", capabilities == null ? 0 : capabilities.size()),
                success ? "ok" : "fallback_stub",
                success ? null : "MCP_FALLBACK_TO_STUB",
                success ? null : true,
                success,
                message,
                traceId
        );
        return capabilities == null ? List.of() : capabilities;
    }

    public Map<String, Object> invoke(String operator, String capability, Map<String, Object> params, Map<String, Object> context) {
        String normalizedCapability = capability == null ? "" : capability.trim();
        Map<String, Object> normalizedContext = context == null ? Map.of() : context;

        Map<String, Object> normalizedParams;
        try {
            normalizedParams = normalizeInvokeParams(normalizedCapability, params);
        } catch (RuntimeException ex) {
            return fallbackPayload(operator, normalizedCapability, params == null ? Map.of() : params, normalizedContext, 1, ex);
        }

        McpCapabilityGateway gateway;
        try {
            gateway = gatewaySelectionStrategy.invokeGateway(normalizedCapability, mode);
        } catch (RuntimeException ex) {
            if (fallbackToStub) {
                Object fallbackResult = stubGateway.invokeCapability(normalizedCapability, normalizedParams);
                record(
                        operator,
                        "MCP_INVOKE",
                        invokeAuditBase(normalizedCapability, 1, normalizedContext),
                        "fallback_stub",
                        errorCodeOf(ex),
                        retryableOf(ex),
                        false,
                        "fallback to stub",
                        traceIdOf(normalizedContext)
                );
                return successPayload(
                        "fallback_stub",
                        normalizedCapability,
                        1,
                        fallbackResult,
                        errorCodeOf(ex),
                        errorMessageOf(ex),
                        retryableOf(ex)
                );
            }
            record(
                    operator,
                    "MCP_INVOKE",
                    invokeAuditBase(normalizedCapability, 1, normalizedContext),
                    "error",
                    errorCodeOf(ex),
                    retryableOf(ex),
                    false,
                    errorMessageOf(ex),
                    traceIdOf(normalizedContext)
            );
            throw ex;
        }

        Throwable lastError = null;
        int actualAttempts = 0;
        for (int attempt = 1; attempt <= retries + 1; attempt++) {
            actualAttempts = attempt;
            try {
                Object result = invoke(
                        gateway,
                        normalizedCapability,
                        normalizedParams,
                        normalizedContext,
                        timeoutMillis
                );
                record(
                        operator,
                        "MCP_INVOKE",
                        invokeAuditBase(normalizedCapability, attempt, normalizedContext),
                        "ok",
                        null,
                        null,
                        true,
                        "ok",
                        traceIdOf(normalizedContext)
                );
                return successPayload("ok", normalizedCapability, attempt, result, null, null, null);
            } catch (Exception ex) {
                lastError = ex;
            }
            if (!isRetryable(lastError)) {
                break;
            }
        }

        if (shouldFallback(gateway)) {
            try {
                Object fallbackResult = stubGateway.invokeCapability(normalizedCapability, normalizedParams);
                record(
                        operator,
                        "MCP_INVOKE",
                        invokeAuditBase(normalizedCapability, actualAttempts, normalizedContext),
                        "fallback_stub",
                        errorCodeOf(lastError),
                        retryableOf(lastError),
                        false,
                        "fallback to stub",
                        traceIdOf(normalizedContext)
                );
                return successPayload(
                        "fallback_stub",
                        normalizedCapability,
                        actualAttempts,
                        fallbackResult,
                        errorCodeOf(lastError),
                        errorMessageOf(lastError),
                        retryableOf(lastError)
                );
            } catch (Exception fallbackError) {
                lastError = fallbackError;
            }
        }

        String reason = errorMessageOf(lastError);
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("status", "fallback");
        fallback.put("capability", normalizedCapability);
        fallback.put("attempt", actualAttempts);
        fallback.put("message", reason);
        fallback.put("errorCode", errorCodeOf(lastError));
        fallback.put("retryable", retryableOf(lastError));
        fallback.put("result", Map.of(
                "capability", normalizedCapability,
                "params", normalizedParams,
                "context", normalizedContext
        ));
        record(
                operator,
                "MCP_INVOKE",
                invokeAuditBase(normalizedCapability, actualAttempts, normalizedContext),
                "fallback",
                errorCodeOf(lastError),
                retryableOf(lastError),
                false,
                reason,
                traceIdOf(normalizedContext)
        );
        return fallback;
    }

    private Map<String, Object> fallbackPayload(String operator,
                                                String capability,
                                                Map<String, Object> params,
                                                Map<String, Object> context,
                                                int attempt,
                                                Throwable error) {
        String reason = errorMessageOf(error);
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("status", "fallback");
        fallback.put("capability", capability);
        fallback.put("attempt", attempt);
        fallback.put("message", reason);
        fallback.put("errorCode", errorCodeOf(error));
        fallback.put("retryable", retryableOf(error));
        fallback.put("result", Map.of(
                "capability", capability,
                "params", params,
                "context", context
        ));
        record(
                operator,
                "MCP_INVOKE",
                invokeAuditBase(capability, attempt, context),
                "fallback",
                errorCodeOf(error),
                retryableOf(error),
                false,
                reason,
                traceIdOf(context)
        );
        return fallback;
    }

    private boolean shouldFallback(McpCapabilityGateway gateway) {
        return fallbackToStub && gateway != stubGateway;
    }

    private boolean isRetryable(Throwable error) {
        if (error instanceof BusinessException ex) {
            return ex.retryable();
        }
        return true;
    }

    private String errorCodeOf(Throwable error) {
        if (error instanceof BusinessException ex) {
            return String.valueOf(ex.errorCode());
        }
        if (error == null) {
            return "MCP_UNKNOWN";
        }
        if (error instanceof java.util.concurrent.TimeoutException) {
            return "MCP_TIMEOUT";
        }
        return "MCP_EXECUTION_FAILED";
    }

    private boolean retryableOf(Throwable error) {
        if (error instanceof BusinessException ex) {
            return ex.retryable();
        }
        return error != null;
    }

    private String errorMessageOf(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return "invoke failed";
        }
        return error.getMessage();
    }

    private Map<String, Object> successPayload(String status,
                                               String capability,
                                               int attempt,
                                               Object result,
                                               String errorCode,
                                               String message,
                                               Boolean retryable) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", status);
        data.put("capability", capability);
        data.put("attempt", attempt);
        data.put("result", result);
        data.put("timestamp", Instant.now().toString());
        if (errorCode != null && !errorCode.isBlank()) {
            data.put("errorCode", errorCode);
        }
        if (message != null && !message.isBlank()) {
            data.put("message", message);
        }
        if (retryable != null) {
            data.put("retryable", retryable);
        }
        return data;
    }

    private String traceIdOf(Map<String, Object> context) {
        if (context == null) {
            return "";
        }
        Object trace = context.get("traceId");
        return trace == null ? "" : String.valueOf(trace).trim();
    }

    private Map<String, Object> invokeAuditBase(String capability, int attempt, Map<String, Object> context) {
        return Map.of(
                "capability", capability,
                "attempt", attempt,
                "contextSize", context == null ? 0 : context.size()
        );
    }

    private List<String> mergeCapabilities(List<String> primaryCapabilities, List<String> extraCapabilities) {
        LinkedHashMap<String, Boolean> merged = new LinkedHashMap<>();
        if (primaryCapabilities != null) {
            for (String item : primaryCapabilities) {
                if (item == null || item.isBlank()) {
                    continue;
                }
                merged.put(item.trim(), true);
            }
        }
        if (extraCapabilities != null) {
            for (String item : extraCapabilities) {
                if (item == null || item.isBlank()) {
                    continue;
                }
                merged.put(item.trim(), true);
            }
        }
        return merged.keySet().stream().toList();
    }

    // ── inlined from GatewayAuditRecorder ──

    private void record(String operator,
                        String action,
                        Map<String, Object> base,
                        String status,
                        String errorCode,
                        Boolean retryable,
                        boolean success,
                        String message,
                        String traceId) {
        opsAuditService.record(
                operator,
                action,
                buildPayload(base, status, errorCode, retryable),
                success,
                message,
                traceId
        );
    }

    private Map<String, Object> buildPayload(Map<String, Object> base,
                                             String status,
                                             String errorCode,
                                             Boolean retryable) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (base != null) {
            payload.putAll(base);
        }
        if (status != null && !status.isBlank()) {
            payload.put("status", status);
        }
        if (errorCode != null && !errorCode.isBlank()) {
            payload.put("errorCode", errorCode);
        }
        if (retryable != null) {
            payload.put("retryable", retryable);
        }
        return payload;
    }

    // ── inlined from CapabilityParamNormalizer ──

    private Map<String, Object> normalizeInvokeParams(String capability, Map<String, Object> params) {
        Map<String, Object> normalizedParams = params == null ? Map.of() : params;
        if (!isFileReadCapability(capability)) {
            return normalizedParams;
        }
        LinkedHashMap<String, Object> adjusted = new LinkedHashMap<>(normalizedParams);
        Integer offset = positiveInteger(adjusted.get("offset"), "offset");
        Integer limit = positiveInteger(adjusted.get("limit"), "limit");
        Integer lineStart = positiveInteger(firstNonNull(adjusted, "lineStart", "fromLine"), "lineStart");
        Integer lineEnd = positiveInteger(firstNonNull(adjusted, "lineEnd", "toLine"), "lineEnd");
        if (lineStart != null && lineEnd != null && lineEnd < lineStart) {
            throw new BusinessException(ErrorCode.MCP_INVALID_PARAMS, "lineEnd 不能小于 lineStart");
        }
        if (offset == null && lineStart != null) {
            offset = lineStart;
        }
        if (limit == null && lineStart != null && lineEnd != null) {
            limit = lineEnd - lineStart + 1;
        }
        if (offset != null) {
            adjusted.put("offset", offset);
        }
        if (limit != null) {
            if (limit > MAX_READ_LIMIT) {
                throw new BusinessException(ErrorCode.MCP_INVALID_PARAMS, "limit 超过最大允许值 " + MAX_READ_LIMIT);
            }
            adjusted.put("limit", limit);
        }
        return adjusted;
    }

    private boolean isFileReadCapability(String capability) {
        if (capability == null) {
            return false;
        }
        String normalized = capability.trim().toLowerCase();
        return FILE_READ_CAPABILITIES.contains(normalized);
    }

    private Object firstNonNull(Map<String, Object> map, String firstKey, String secondKey) {
        if (map == null) {
            return null;
        }
        Object first = map.get(firstKey);
        if (first != null) {
            return first;
        }
        return map.get(secondKey);
    }

    private Integer positiveInteger(Object rawValue, String field) {
        if (rawValue == null) {
            return null;
        }
        int parsed;
        if (rawValue instanceof Number number) {
            parsed = number.intValue();
        } else {
            String text = String.valueOf(rawValue).trim();
            if (text.isBlank()) {
                return null;
            }
            try {
                parsed = Integer.parseInt(text);
            } catch (NumberFormatException ex) {
                throw new BusinessException(ErrorCode.MCP_INVALID_PARAMS, field + " 必须为正整数");
            }
        }
        if (parsed <= 0) {
            throw new BusinessException(ErrorCode.MCP_INVALID_PARAMS, field + " 必须大于 0");
        }
        return parsed;
    }

    // ── inlined from GatewayInvocationExecutor ──

    private Object invoke(McpCapabilityGateway gateway,
                          String capability,
                          Map<String, Object> params,
                          Map<String, Object> context,
                          int timeoutMillis) {
        try {
            return CompletableFuture
                    .supplyAsync(() -> gateway.invokeCapability(capability, params, context))
                    .orTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                    .join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof TimeoutException timeoutException) {
                throw new BusinessException(ErrorCode.MCP_TIMEOUT, timeoutException.getMessage() == null ? "invoke timeout" : timeoutException.getMessage());
            }
            throw new RuntimeException(cause);
        }
    }
}






