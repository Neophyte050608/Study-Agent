package com.example.interview.service;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 运维/操作审计服务。
 *
 * <p>用途：记录关键运维与可观测操作（例如任务分发、MCP 调用、DLQ 重放、幂等清理）。</p>
 *
 * <p>当前实现为进程内环形缓冲（近似）：</p>
 * <ul>
 *   <li>写入：追加到内存列表。</li>
 *   <li>容量：超过 MAX_RECORDS 时淘汰最旧记录，控制内存占用。</li>
 *   <li>脱敏：对 payload 中可能较长的 message 做截断，减少日志/接口返回的负担。</li>
 * </ul>
 */
@Service
public class OpsAuditService {

    private static final int MAX_RECORDS = 2000;
    // 当前采用进程内审计缓冲，后续可迁移到持久化存储。
    private final List<OpsAuditRecord> records = new CopyOnWriteArrayList<>();

    public void record(String operator, String action, Map<String, Object> payload, boolean success, String message) {
        record(operator, action, payload, success, message, "");
    }

    /**
     * 记录审计日志。
     * 
     * @param operator 操作人 ID
     * @param action 操作行为（如：MCP_INVOKE, TASK_DISPATCH）
     * @param payload 操作关联的数据载荷（会自动进行脱敏截断）
     * @param success 是否操作成功
     * @param message 操作相关的描述信息或错误提示
     * @param traceId 可选，关联的调用链路追踪 ID
     */
    public void record(String operator, String action, Map<String, Object> payload, boolean success, String message, String traceId) {
        records.add(new OpsAuditRecord(
                operator == null || operator.isBlank() ? "anonymous" : operator.trim(),
                action == null ? "" : action,
                payload == null ? Map.of() : sanitize(payload),
                success,
                message == null ? "" : message,
                traceId == null ? "" : traceId.trim(),
                Instant.now().toString()
        ));
        if (records.size() > MAX_RECORDS) {
            // 控制内存上限，按时间顺序淘汰最旧记录。
            int overflow = records.size() - MAX_RECORDS;
            if (overflow > 0) {
                records.subList(0, overflow).clear();
            }
        }
    }

    public List<OpsAuditRecord> listRecent(int limit) {
        int normalized = limit <= 0 ? 20 : Math.min(limit, 200);
        int size = records.size();
        if (size == 0) {
            return List.of();
        }
        int start = Math.max(0, size - normalized);
        List<OpsAuditRecord> view = new ArrayList<>(records.subList(start, size));
        java.util.Collections.reverse(view);
        return view;
    }

    public List<OpsAuditRecord> listFiltered(int limit, String actionPrefix, String traceId) {
        return listFiltered(limit, actionPrefix, traceId, "");
    }

    public List<OpsAuditRecord> listFiltered(int limit, String actionPrefix, String traceId, String errorCode) {
        int normalized = limit <= 0 ? 20 : Math.min(limit, 200);
        String prefix = actionPrefix == null ? "" : actionPrefix.trim();
        String trace = traceId == null ? "" : traceId.trim();
        String normalizedErrorCode = errorCode == null ? "" : errorCode.trim();
        List<OpsAuditRecord> filtered = records.stream()
                .filter(record -> prefix.isBlank() || record.action().startsWith(prefix))
                .filter(record -> trace.isBlank() || trace.equals(record.traceId()))
                .filter(record -> normalizedErrorCode.isBlank() || normalizedErrorCode.equals(errorCodeOf(record)))
                .toList();
        int size = filtered.size();
        if (size == 0) {
            return List.of();
        }
        int start = Math.max(0, size - normalized);
        List<OpsAuditRecord> view = new ArrayList<>(filtered.subList(start, size));
        java.util.Collections.reverse(view);
        return view;
    }

    private String errorCodeOf(OpsAuditRecord record) {
        Object value = record.payload().get("errorCode");
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Map<String, Object> sanitize(Map<String, Object> payload) {
        // 审计 payload 允许携带结构化字段，但对 message 等长文本做截断，避免误把大段内容写入内存与响应。
        Map<String, Object> safe = new LinkedHashMap<>();
        payload.forEach((key, value) -> {
            String k = key == null ? "" : key;
            if ("message".equalsIgnoreCase(k)) {
                String text = value == null ? "" : value.toString();
                safe.put(k, text.length() > 240 ? text.substring(0, 240) : text);
                return;
            }
            safe.put(k, value);
        });
        return safe;
    }

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
}
