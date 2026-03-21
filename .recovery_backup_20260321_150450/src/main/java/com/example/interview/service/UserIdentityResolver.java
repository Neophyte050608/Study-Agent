package com.example.interview.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 用户身份解析器（面向服务端的“轻量识别”）。
 *
 * <p>优先级：</p>
 * <ol>
 *   <li>Authorization: Bearer ...：如果是 JWT 且未过期，优先取 sub/uid 作为主体标识；</li>
 *   <li>X-User-Id：用于本地/内网调试的简易头；</li>
 *   <li>HttpSession：最后兜底，用 sessionId 保证同一浏览器会话内一致。</li>
 * </ol>
 *
 * <p>安全注意：非 JWT token 不会原样写入标识，而是做哈希截断，避免把敏感凭证暴露到日志/审计/数据库。</p>
 */
@Component
public class UserIdentityResolver {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String resolve(HttpServletRequest request) {
        // 单用户模式：直接返回默认用户ID，忽略一切请求头
        return "default-user";
    }

    private String resolveFromBearer(String authorization) {
        if (authorization == null) {
            return "";
        }
        String trimmed = authorization.trim();
        if (!trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return "";
        }
        String token = trimmed.substring(7).trim();
        if (token.isBlank()) {
            return "";
        }
        boolean jwtLike = token.contains(".");

        // JWT 场景：只解析 payload 的 sub/uid，不校验签名（本组件只负责“识别”，鉴权应由安全层实现）。
        String subject = extractJwtSubject(token);
        if (!subject.isBlank()) {
            return "subject:" + subject;
        }
        if (jwtLike) {
            // 看起来像 JWT 但无法解析出主体（或已过期），直接放弃，避免把可能的 JWT 原文散落到系统里。
            return "";
        }

        // 非 JWT：返回哈希截断值，避免存储/暴露原始 token。
        return "token:" + hashToken(token);
    }

    private String extractJwtSubject(String token) {
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            return "";
        }
        try {
            // 只解 base64url 的 payload（第二段），不尝试解析 header/signature。
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            String payload = new String(payloadBytes, StandardCharsets.UTF_8);
            JsonNode node = objectMapper.readTree(payload);
            long exp = node.path("exp").asLong(0);
            if (exp > 0 && Instant.now().getEpochSecond() >= exp) {
                return "";
            }
            String sub = node.path("sub").asText("");
            if (!sub.isBlank()) {
                return sanitizePrincipal(sub);
            }
            String uid = node.path("uid").asText("");
            return sanitizePrincipal(uid);
        } catch (Exception ignored) {
            return "";
        }
    }

    private String sanitizePrincipal(String value) {
        if (value == null) {
            return "";
        }
        // 防止 header 注入/日志污染：去掉控制字符并限制长度。
        String cleaned = value.trim().replaceAll("[\\r\\n\\t]", "");
        if (cleaned.isBlank()) {
            return "";
        }
        if (cleaned.length() > 64) {
            cleaned = cleaned.substring(0, 64);
        }
        return cleaned;
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(token.hashCode());
        }
    }
}
