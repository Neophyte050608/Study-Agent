package io.github.imzmq.interview.chat.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    // ==================== Layer 3+4+5: 解析/校验/重试/兜底 ====================

    /**
     * 解析 LLM 响应为 JsonNode，应用五层防御。
     *
     * @param raw    LLM 原始响应
     * @param schema Schema 约束（可选，传 null 跳过校验）
     * @param retry  重试回调（可选，传 null 跳过重试）
     * @return 解析结果
     */
    public JsonResult<JsonNode> parseTree(String raw, SchemaSpec schema, RetryCall retry) {
        List<String> allWarnings = new ArrayList<>();
        String current = raw;
        int attempt = 0;
        String lastFailureReason = null;

        while (true) {
            attempt++;

            // --- Layer 1: 提取 ---
            String extracted = extractJson(current);
            if (extracted == null) {
                lastFailureReason = "无法从 LLM 响应中提取 JSON 片段";
                if (shouldRetry(attempt, retry)) {
                    current = doRetry(retry, buildSyntaxHint(null), attempt);
                    if (current != null) continue;
                }
                break;
            }

            // --- Layer 2: 修复 ---
            String repaired = repairJson(extracted);
            if (!repaired.equals(extracted)) {
                allWarnings.add("JSON 已自动修复");
            }

            // --- Layer 3: 解析 ---
            JsonNode node;
            try {
                node = objectMapper.readTree(repaired);
            } catch (Exception parseEx) {
                lastFailureReason = "JSON 解析失败: " + parseEx.getMessage();
                if (shouldRetry(attempt, retry)) {
                    current = doRetry(retry, buildSyntaxHint(parseEx.getMessage()), attempt);
                    if (current != null) continue;
                }
                break;
            }

            // --- Schema 校验 ---
            if (schema != null && !schema.isEmpty()) {
                ValidationReport report = validate(node, schema);
                allWarnings.addAll(report.warnings());
                if (!report.isValid()) {
                    lastFailureReason = "Schema 校验失败: 缺少字段 " + String.join(", ", report.missingFields());
                    if (shouldRetry(attempt, retry)) {
                        current = doRetry(retry, buildSchemaHint(report), attempt);
                        if (current != null) continue;
                    }
                    break;
                }
            }

            return JsonResult.success(node, attempt, allWarnings);
        }

        log.debug("JSON 解析最终失败: reason={}, attempts={}", lastFailureReason, attempt);
        return JsonResult.failure(lastFailureReason != null ? lastFailureReason : "未知解析失败", attempt);
    }

    private boolean shouldRetry(int attempt, RetryCall retry) {
        return attempt <= MAX_RETRIES && retry != null;
    }

    private String doRetry(RetryCall retry, String hint, int attempt) {
        try {
            return retry.retry(hint, attempt);
        } catch (Exception e) {
            log.debug("重试调用异常: attempt={}, error={}", attempt, e.getMessage());
            return null;
        }
    }

    // ==================== Schema 校验 ====================

    private ValidationReport validate(JsonNode node, SchemaSpec schema) {
        List<String> missing = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (String field : schema.requiredFields()) {
            if (!node.has(field) || node.get(field).isNull()) {
                missing.add(field);
            }
        }

        for (Map.Entry<String, SchemaSpec.JsonType> entry : schema.fieldTypes().entrySet()) {
            String field = entry.getKey();
            SchemaSpec.JsonType expectedType = entry.getValue();
            if (!node.has(field) || node.get(field).isNull()) {
                continue;
            }
            JsonNode fieldNode = node.get(field);
            boolean typeOk = switch (expectedType) {
                case STRING -> fieldNode.isTextual();
                case NUMBER -> fieldNode.isNumber();
                case BOOLEAN -> fieldNode.isBoolean();
                case ARRAY -> fieldNode.isArray();
                case OBJECT -> fieldNode.isObject();
            };
            if (!typeOk) {
                warnings.add(field + " 类型不匹配，期望 " + expectedType);
            }
        }

        for (Map.Entry<String, Set<String>> entry : schema.fieldAllowedValues().entrySet()) {
            String field = entry.getKey();
            Set<String> allowed = entry.getValue();
            if (!node.has(field) || node.get(field).isNull()) {
                continue;
            }
            String value = node.get(field).asText("").trim().toUpperCase();
            if (!value.isBlank() && !allowed.contains(value)) {
                warnings.add(field + "=" + value + " 不在允许值内");
            }
        }

        return new ValidationReport(missing.isEmpty(), missing, warnings);
    }

    private record ValidationReport(boolean isValid, List<String> missingFields, List<String> warnings) {}

    // ==================== 重试提示构建 ====================

    private String buildSyntaxHint(String parseError) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n【格式修正要求】上一次返回的 JSON 无法解析。");
        if (parseError != null && !parseError.isBlank()) {
            sb.append(" 错误: ").append(parseError);
        }
        sb.append("\n请务必: 1) 只返回纯 JSON，不要包含解释文字或 markdown 标记；");
        sb.append("2) 使用双引号而非单引号；3) 不要在最后一个元素后加逗号。");
        return sb.toString();
    }

    private String buildSchemaHint(ValidationReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n【格式修正要求】上一次返回的 JSON 缺少必填字段: ");
        sb.append(String.join(", ", report.missingFields()));
        sb.append("。请确保 JSON 中包含这些字段并赋予正确的值。只返回纯 JSON。");
        return sb.toString();
    }
}
