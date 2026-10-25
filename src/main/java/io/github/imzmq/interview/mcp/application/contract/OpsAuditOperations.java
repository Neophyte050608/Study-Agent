package io.github.imzmq.interview.mcp.application.contract;

import java.util.List;
import java.util.Map;

/** Public operational audit write and query surface. */
public interface OpsAuditOperations {

    void record(String operator, String action, Map<String, Object> payload, boolean success, String message);

    void record(String operator, String action, Map<String, Object> payload, boolean success, String message, String traceId);

    List<OpsAuditRecord> listRecent(int limit);

    List<OpsAuditRecord> listFiltered(int limit, String actionPrefix, String traceId);

    List<OpsAuditRecord> listFiltered(int limit, String actionPrefix, String traceId, String errorCode);
}
