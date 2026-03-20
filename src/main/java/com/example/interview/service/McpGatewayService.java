package com.example.interview.service;

import com.example.interview.tool.McpCapabilityGateway;
import com.example.interview.tool.McpBridgeCapabilityGateway;
import com.example.interview.tool.McpGatewayException;
import com.example.interview.tool.McpSseCapabilityGateway;
import com.example.interview.tool.McpStdioCapabilityGateway;
import com.example.interview.tool.StubMcpCapabilityGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * MCP（工具能力）网关服务。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>按 mode 选择主网关（stub/bridge/sse/stdio/auto）。</li>
 *   <li>为 invoke 提供统一的超时、重试与降级（fallback-to-stub）。</li>
 *   <li>把每次调用写入 OpsAudit，便于排障与观测（支持 traceId 关联）。</li>
 * </ul>
 *
 * <p>返回结构：</p>
 * <ul>
 *   <li>status=ok：主网关成功。</li>
 *   <li>status=fallback_stub：主网关失败但 stub 返回成功（功能可用性优先）。</li>
 *   <li>status=fallback：主网关与 stub 都失败，返回包含错误码与上下文的降级结果。</li>
 * </ul>
 */
@Service
public class McpGatewayService {

    private final McpCapabilityGateway stubGateway;
    private final McpCapabilityGateway bridgeGateway;
    private final McpCapabilityGateway sseGateway;
    private final McpCapabilityGateway stdioGateway;
    private final OpsAuditService opsAuditService;
    private final int timeoutMillis;
    private final int retries;
    private final String mode;
    private final boolean fallbackToStub;

    @Autowired
    public McpGatewayService(
            StubMcpCapabilityGateway stubGateway,
            @Autowired(required = false) McpBridgeCapabilityGateway bridgeGateway,
            @Autowired(required = false) McpSseCapabilityGateway sseGateway,
            @Autowired(required = false) McpStdioCapabilityGateway stdioGateway,
            OpsAuditService opsAuditService,
            @Value("${app.mcp.timeout-millis:1500}") int timeoutMillis,
            @Value("${app.mcp.retries:2}") int retries,
            @Value("${app.mcp.mode:stub}") String mode,
            @Value("${app.mcp.fallback-to-stub:true}") boolean fallbackToStub
    ) {
        this(
                (McpCapabilityGateway) stubGateway,
                (McpCapabilityGateway) bridgeGateway,
                (McpCapabilityGateway) sseGateway,
                (McpCapabilityGateway) stdioGateway,
                opsAuditService,
                timeoutMillis,
                retries,
                mode,
                fallbackToStub
        );
    }

    McpGatewayService(
            McpCapabilityGateway stubGateway,
            McpCapabilityGateway bridgeGateway,
            McpCapabilityGateway sseGateway,
            McpCapabilityGateway stdioGateway,
            OpsAuditService opsAuditService,
            int timeoutMillis,
            int retries,
            String mode,
            boolean fallbackToStub
    ) {
        this.stubGateway = stubGateway;
        this.bridgeGateway = bridgeGateway;
        this.sseGateway = sseGateway;
        this.stdioGateway = stdioGateway;
        this.opsAuditService = opsAuditService;
        this.timeoutMillis = Math.max(200, timeoutMillis);
        this.retries = Math.max(0, retries);
        this.mode = mode == null ? "stub" : mode.trim().toLowerCase();
        this.fallbackToStub = fallbackToStub;
    }

    McpGatewayService(
            McpCapabilityGateway stubGateway,
            McpCapabilityGateway bridgeGateway,
            OpsAuditService opsAuditService,
            int timeoutMillis,
            int retries,
            String mode,
            boolean fallbackToStub
    ) {
        this(stubGateway, bridgeGateway, null, null, opsAuditService, timeoutMillis, retries, mode, fallbackToStub);
    }

    public List<String> discoverCapabilities(String operator) {
        return discoverCapabilities(operator, "");
    }

    public List<String> discoverCapabilities(String operator, String traceId) {
        McpCapabilityGateway gateway;
        try {
            gateway = primaryGateway();
        } catch (RuntimeException ex) {
            if (fallbackToStub) {
                // 主网关不可用时允许回退到 stub（通常用于本地开发或中间件未配置场景）。
                List<String> capabilities = stubGateway.listCapabilities();
                opsAuditService.record(
                        operator,
                        "MCP_DISCOVER_CAPABILITIES",
                        auditPayload(
                                Map.of("count", capabilities == null ? 0 : capabilities.size()),
                                "fallback_stub",
                                errorCodeOf(ex),
                                retryableOf(ex)
                        ),
                        false,
                        "fallback to stub",
                        traceId
                );
                return capabilities == null ? List.of() : capabilities;
            }
            opsAuditService.record(
                    operator,
                    "MCP_DISCOVER_CAPABILITIES",
                    auditPayload(Map.of("count", 0), "error", errorCodeOf(ex), retryableOf(ex)),
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
            capabilities = gateway.listCapabilities();
        } catch (RuntimeException ex) {
            if (shouldFallback(gateway)) {
                // 主网关运行期失败：若允许降级则仍返回 stub 能力列表，但记录审计为非成功。
                capabilities = stubGateway.listCapabilities();
                success = false;
                message = "fallback to stub";
            } else {
                opsAuditService.record(
                        operator,
                        "MCP_DISCOVER_CAPABILITIES",
                        auditPayload(Map.of("count", 0), "error", errorCodeOf(ex), retryableOf(ex)),
                        false,
                        errorMessageOf(ex),
                        traceId
                );
                throw ex;
            }
        }
        opsAuditService.record(
                operator,
                "MCP_DISCOVER_CAPABILITIES",
                auditPayload(
                        Map.of("count", capabilities == null ? 0 : capabilities.size()),
                        success ? "ok" : "fallback_stub",
                        success ? null : "MCP_FALLBACK_TO_STUB",
                        success ? null : true
                ),
                success,
                message,
                traceId
        );
        return capabilities == null ? List.of() : capabilities;
    }

    public Map<String, Object> invoke(String operator, String capability, Map<String, Object> params, Map<String, Object> context) {
        String normalizedCapability = capability == null ? "" : capability.trim();
        Map<String, Object> normalizedParams = params == null ? Map.of() : params;
        Map<String, Object> normalizedContext = context == null ? Map.of() : context;
        McpCapabilityGateway gateway;
        try {
            gateway = primaryGateway();
        } catch (RuntimeException ex) {
            if (fallbackToStub) {
                // 选择主网关阶段失败：直接走 stub，避免前端“不可用”。
                Object fallbackResult = stubGateway.invokeCapability(normalizedCapability, normalizedParams);
                Map<String, Object> data = successPayload(
                        "fallback_stub",
                        normalizedCapability,
                        1,
                        fallbackResult,
                        errorCodeOf(ex),
                        errorMessageOf(ex),
                        retryableOf(ex)
                );
                opsAuditService.record(
                        operator,
                        "MCP_INVOKE",
                        auditPayload(
                                Map.of(
                                "capability", normalizedCapability,
                                "attempt", 1,
                                "contextSize", normalizedContext.size()
                                ),
                                "fallback_stub",
                                errorCodeOf(ex),
                                retryableOf(ex)
                        ),
                        false,
                        "fallback to stub",
                        traceIdOf(normalizedContext)
                );
                return data;
            }
            opsAuditService.record(
                    operator,
                    "MCP_INVOKE",
                    auditPayload(
                            Map.of(
                                    "capability", normalizedCapability,
                                    "attempt", 1,
                                    "contextSize", normalizedContext.size()
                            ),
                            "error",
                            errorCodeOf(ex),
                            retryableOf(ex)
                    ),
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
                // 用异步 + orTimeout 包装网关调用，避免阻塞请求线程并统一处理超时。
                Object result = CompletableFuture
                        .supplyAsync(() -> gateway.invokeCapability(normalizedCapability, normalizedParams, normalizedContext))
                        .orTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                        .join();
                Map<String, Object> data = successPayload("ok", normalizedCapability, attempt, result, null, null, null);
                opsAuditService.record(
                        operator,
                        "MCP_INVOKE",
                        auditPayload(
                                Map.of(
                                "capability", normalizedCapability,
                                "attempt", attempt,
                                "contextSize", normalizedContext.size()
                                ),
                                "ok",
                                null,
                                null
                        ),
                        true,
                        "ok",
                        traceIdOf(normalizedContext)
                );
                return data;
            } catch (CompletionException ex) {
                lastError = ex.getCause() == null ? ex : ex.getCause();
            } catch (Exception ex) {
                lastError = ex;
            }
            if (!isRetryable(lastError)) {
                break;
            }
        }
        if (shouldFallback(gateway)) {
            try {
                // 主网关多次失败后再降级：避免所有错误都立即走 stub，掩盖真实问题。
                Object fallbackResult = stubGateway.invokeCapability(normalizedCapability, normalizedParams);
                Map<String, Object> data = successPayload(
                        "fallback_stub",
                        normalizedCapability,
                        actualAttempts,
                        fallbackResult,
                        errorCodeOf(lastError),
                        errorMessageOf(lastError),
                        retryableOf(lastError)
                );
                opsAuditService.record(
                        operator,
                        "MCP_INVOKE",
                        auditPayload(
                                Map.of(
                                "capability", normalizedCapability,
                                "attempt", actualAttempts,
                                "contextSize", normalizedContext.size()
                                ),
                                "fallback_stub",
                                errorCodeOf(lastError),
                                retryableOf(lastError)
                        ),
                        false,
                        "fallback to stub",
                        traceIdOf(normalizedContext)
                );
                return data;
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
        opsAuditService.record(
                operator,
                "MCP_INVOKE",
                auditPayload(
                        Map.of(
                        "capability", normalizedCapability,
                        "attempt", actualAttempts,
                        "contextSize", normalizedContext.size()
                        ),
                        "fallback",
                        errorCodeOf(lastError),
                        retryableOf(lastError)
                ),
                false,
                reason,
                traceIdOf(normalizedContext)
        );
        return fallback;
    }

    private McpCapabilityGateway primaryGateway() {
        // mode 明确指定时必须有对应网关，否则抛出“不可重试/可重试”明确的业务异常，便于上层决策。
        if ("sse".equals(mode) && sseGateway != null) {
            return sseGateway;
        }
        if ("sse".equals(mode)) {
            throw new McpGatewayException("MCP_SSE_NOT_CONFIGURED", false, "mcp sse is not configured");
        }
        if ("bridge".equals(mode) && bridgeGateway != null) {
            return bridgeGateway;
        }
        if ("bridge".equals(mode)) {
            throw new McpGatewayException("MCP_BRIDGE_NOT_CONFIGURED", false, "mcp bridge is not configured");
        }
        if ("stdio".equals(mode) && stdioGateway != null) {
            return stdioGateway;
        }
        if ("stdio".equals(mode)) {
            throw new McpGatewayException("MCP_STDIO_NOT_CONFIGURED", false, "mcp stdio is not configured");
        }
        if ("auto".equals(mode) && sseGateway != null) {
            return sseGateway;
        }
        if ("auto".equals(mode) && stdioGateway != null) {
            return stdioGateway;
        }
        if ("auto".equals(mode) && bridgeGateway != null) {
            return bridgeGateway;
        }
        // stub 永远存在：用于本地开发与兜底降级。
        return stubGateway;
    }

    private boolean shouldFallback(McpCapabilityGateway gateway) {
        return fallbackToStub && gateway != stubGateway;
    }

    private boolean isRetryable(Throwable error) {
        if (error instanceof McpGatewayException ex) {
            return ex.isRetryable();
        }
        return true;
    }

    private String errorCodeOf(Throwable error) {
        if (error instanceof McpGatewayException ex) {
            return ex.getErrorCode();
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
        if (error instanceof McpGatewayException ex) {
            return ex.isRetryable();
        }
        return error != null;
    }

    private String errorMessageOf(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return "invoke failed";
        }
        return error.getMessage();
    }

    private Map<String, Object> successPayload(
            String status,
            String capability,
            int attempt,
            Object result,
            String errorCode,
            String message,
            Boolean retryable
    ) {
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

    private Map<String, Object> auditPayload(
            Map<String, Object> base,
            String status,
            String errorCode,
            Boolean retryable
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (base != null) {
            data.putAll(base);
        }
        if (status != null && !status.isBlank()) {
            data.put("status", status);
        }
        if (errorCode != null && !errorCode.isBlank()) {
            data.put("errorCode", errorCode);
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
}
