package io.github.imzmq.interview.mcp.application;

import io.github.imzmq.interview.common.api.BusinessException;
import io.github.imzmq.interview.common.api.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class CapabilityParamNormalizer {

    private static final int MAX_READ_LIMIT = 2000;
    private static final Set<String> FILE_READ_CAPABILITIES = Set.of(
            "obsidian.read",
            "file.read",
            "filesystem.read",
            "mcp.file.read"
    );

    public Map<String, Object> normalizeInvokeParams(String capability, Map<String, Object> params) {
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
}



