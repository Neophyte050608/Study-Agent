package com.example.interview.tool;

/**
 * MCP 网关专用异常。
 *
 * <p>相比通用 RuntimeException，多携带：</p>
 * <ul>
 *   <li>errorCode：用于前端与审计聚合统计（例如 MCP_TIMEOUT、MCP_SSE_NOT_CONFIGURED）。</li>
 *   <li>retryable：标识该错误是否建议重试（例如配置缺失通常不可重试）。</li>
 * </ul>
 */
public class McpGatewayException extends RuntimeException {
    private final String errorCode;
    private final boolean retryable;

    public McpGatewayException(String errorCode, boolean retryable, String message) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public McpGatewayException(String errorCode, boolean retryable, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
