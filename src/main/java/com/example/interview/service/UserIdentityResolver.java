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

@Component
public class UserIdentityResolver {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String resolve(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        String fromToken = resolveFromBearer(authorization);
        if (!fromToken.isBlank()) {
            return fromToken;
        }

        String fromHeader = request.getHeader("X-User-Id");
        if (fromHeader != null && !fromHeader.isBlank()) {
            return "header:" + fromHeader.trim();
        }

        String sessionId = request.getSession(true).getId();
        return "session:" + sessionId;
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
        String subject = extractJwtSubject(token);
        if (!subject.isBlank()) {
            return "subject:" + subject;
        }
        if (jwtLike) {
            return "";
        }
        return "token:" + hashToken(token);
    }

    private String extractJwtSubject(String token) {
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            return "";
        }
        try {
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
