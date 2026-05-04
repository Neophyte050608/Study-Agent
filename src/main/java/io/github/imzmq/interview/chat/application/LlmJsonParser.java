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

    // ==================== Layer 2: JSON 修复 ====================

    /**
     * 修复 LLM 输出中常见的 JSON 语法错误。
     * 按顺序修复 7 类问题：注释、单引号、无引号键、尾逗号、连续对象、截断。
     */
    String repairJson(String jsonLike) {
        if (jsonLike == null || jsonLike.isBlank()) {
            return jsonLike;
        }
        String repaired = jsonLike.trim();

        // 1. 移除注释（在引号处理之前，避免注释内的引号干扰）
        repaired = removeComments(repaired);

        // 2. 单引号 → 双引号
        repaired = fixSingleQuotes(repaired);

        // 3. 无引号键名 → 加双引号
        repaired = fixUnquotedKeys(repaired);

        // 4. 尾逗号
        repaired = fixTrailingCommas(repaired);

        // 5. 连续对象合并
        repaired = fixConcatenatedObjects(repaired);

        // 6. 截断补全
        repaired = fixTruncation(repaired);

        return repaired;
    }

    private String removeComments(String s) {
        s = s.replaceAll("(?m)^\\s*//.*$", "");
        s = s.replaceAll("/\\*[\\s\\S]*?\\*/", "");
        return s;
    }

    private String fixSingleQuotes(String s) {
        StringBuilder sb = new StringBuilder();
        boolean inDouble = false;
        boolean inSingle = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                sb.append(c);
            } else if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                sb.append('"');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String fixUnquotedKeys(String s) {
        // 匹配 JSON 对象中未加引号的键名: ,{ 或 { 后跟空白 + 标识符 + 空白 + :
        return s.replaceAll("(?<=[,{])\\s*(\\w+)\\s*:", "\"$1\":");
    }

    private String fixTrailingCommas(String s) {
        s = s.replaceAll(",\\s*}", "}");
        s = s.replaceAll(",\\s*]", "]");
        return s;
    }

    private String fixConcatenatedObjects(String s) {
        String fixed = s.replaceAll("\\}\\s*\\{", "},{");
        if (fixed.startsWith("{") && fixed.contains("},{") && !fixed.startsWith("[")) {
            fixed = "[" + fixed + "]";
        }
        return fixed;
    }

    private String fixTruncation(String s) {
        int braceDepth = 0;
        int bracketDepth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
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
            if (c == '{') braceDepth++;
            else if (c == '}') braceDepth--;
            else if (c == '[') bracketDepth++;
            else if (c == ']') bracketDepth--;
        }

        StringBuilder sb = new StringBuilder(s);
        // 关闭未闭合的字符串
        if (inString) {
            sb.append('"');
        }
        for (int i = 0; i < bracketDepth; i++) sb.append(']');
        for (int i = 0; i < braceDepth; i++) sb.append('}');
        return sb.toString();
    }

    public JsonResult<JsonNode> parseTree(String raw, SchemaSpec schema, RetryCall retry) {
        throw new UnsupportedOperationException("待实现");
    }
}
