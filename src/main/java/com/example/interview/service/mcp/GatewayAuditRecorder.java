package com.example.interview.service.mcp;

import com.example.interview.service.OpsAuditService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class GatewayAuditRecorder {

    private final OpsAuditService opsAuditService;

    public GatewayAuditRecorder(OpsAuditService opsAuditService) {
        this.opsAuditService = opsAuditService;
    }

    public void record(String operator,
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
}
