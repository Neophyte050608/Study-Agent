package io.github.imzmq.interview.mcp.application;

import io.github.imzmq.interview.tool.gateway.McpCapabilityGateway;
import io.github.imzmq.interview.common.api.BusinessException;
import io.github.imzmq.interview.common.api.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class GatewayInvocationExecutor {

    public Object invoke(McpCapabilityGateway gateway,
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



