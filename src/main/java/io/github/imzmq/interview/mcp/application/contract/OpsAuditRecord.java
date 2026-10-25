package io.github.imzmq.interview.mcp.application.contract;

import java.util.Map;

public record OpsAuditRecord(
        String operator,
        String action,
        Map<String, Object> payload,
        boolean success,
        String message,
        String traceId,
        String timestamp
) {
}
