package io.github.imzmq.interview.chat.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM JSON 鲁棒解析器——五层洋葱防御模型。
 *
 * Layer 1: JSON 提取（markdown fence / 混杂文本中定位 JSON 片段）
 * Layer 2: JSON 修复（尾逗号、单引号、无引号键、截断等 7 种常见错误）
 * Layer 3: 解析 + Schema 校验（Jackson parse + 必填/类型/枚举检查）
 * Layer 4: 重试（携带修复提示重新调用 LLM）
 * Layer 5: 兜底（返回 JsonResult.failure，由调用方决策）
 */
@Component
public class LlmJsonParser {

    private static final Logger log = LoggerFactory.getLogger(LlmJsonParser.class);
    private static final int MAX_RETRIES = 2;
    private static final Pattern MARKDOWN_FENCE = Pattern.compile("```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```");

    private final ObjectMapper objectMapper;

    public LlmJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ==================== Layer 1: JSON 提取 ====================

    /**
     * 从 LLM 原始响应中提取 JSON 片段。
     * 策略：直接匹配 → 去 markdown fence → 括号平衡定位。
     *
     * @param raw LLM 原始响应文本
     * @return JSON 候选字符串，无法提取时返回 null
     */
    String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();

        // 策略1：直接就是 JSON
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }

        // 策略2：markdown 代码块包裹
        String noFence = stripMarkdownFence(trimmed);
        if (noFence != null) {
            return noFence;
        }

        // 策略3：从混杂文本中定位第一个完整 JSON 对象/数组
        return extractBalancedJson(trimmed);
    }

    private String stripMarkdownFence(String text) {
        Matcher matcher = MARKDOWN_FENCE.matcher(text);
        if (matcher.find()) {
            String inner = matcher.group(1);
            return inner != null ? inner.trim() : null;
        }
        return null;
    }

    private String extractBalancedJson(String text) {
        int start = -1;
        char startChar = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{' || c == '[') {
                start = i;
                startChar = c;
                break;
            }
        }
        if (start < 0) {
            return null;
        }

        char endChar = (startChar == '{') ? '}' : ']';
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == startChar) {
                depth++;
            } else if (c == endChar) {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1).trim();
                }
            }
        }
        return null;
    }

    // ==================== 占位方法（后续 Task 实现） ====================

    String repairJson(String jsonLike) {
        throw new UnsupportedOperationException("待实现");
    }

    public JsonResult<JsonNode> parseTree(String raw, SchemaSpec schema, RetryCall retry) {
        throw new UnsupportedOperationException("待实现");
    }
}
