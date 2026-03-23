package com.example.interview.im.service;

import com.example.interview.im.config.QqProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

/**
 * QQ 鉴权令牌提供器
 * 负责优先使用 appId + appSecret 获取官方 access_token，
 * 并在失败时回退到本地 token 配置兼容模式。
 */
@Service
public class QqAuthTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(QqAuthTokenProvider.class);
    private static final String TOKEN_API_BASE_URL = "https://bots.qq.com";

    private final QqProperties qqProperties;
    private final RestClient restClient;

    private volatile String cachedAccessToken;
    private volatile long accessTokenExpireAtMs;

    public QqAuthTokenProvider(QqProperties qqProperties, RestClient.Builder restClientBuilder) {
        this.qqProperties = qqProperties;
        this.restClient = restClientBuilder.baseUrl(TOKEN_API_BASE_URL).build();
    }

    /**
     * 获取用于 QQ WebSocket Identify 的 token 字段值。
     */
    public String resolveGatewayToken() {
        String accessToken = resolveAccessToken();
        if (accessToken != null && !accessToken.isBlank()) {
            return "QQBot " + accessToken;
        }
        return buildLegacyAuthorizationToken();
    }

    /**
     * 获取用于 QQ OpenAPI 请求头的 Authorization 值。
     */
    public String resolveAuthorizationHeader() {
        return resolveGatewayToken();
    }

    /**
     * 失效缓存，用于鉴权失败后触发重新拉取。
     */
    public void invalidate() {
        cachedAccessToken = null;
        accessTokenExpireAtMs = 0L;
    }

    /**
     * 解析 access_token，优先读取缓存。
     */
    private String resolveAccessToken() {
        long now = System.currentTimeMillis();
        if (cachedAccessToken != null && now < accessTokenExpireAtMs - 60_000L) {
            return cachedAccessToken;
        }

        String appId = qqProperties.getAppId();
        String appSecret = qqProperties.getAppSecret();
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            return null;
        }

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("appId", appId);
            payload.put("clientSecret", appSecret);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri("/app/getAppAccessToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                return null;
            }

            Object tokenObj = response.get("access_token");
            if (tokenObj == null) {
                tokenObj = response.get("accessToken");
            }
            if (tokenObj == null) {
                log.warn("【QQ鉴权】获取 access_token 响应中无 access_token 字段: {}", response);
                return null;
            }

            String token = String.valueOf(tokenObj);
            long expiresInSec = 7200L;
            Object expiresObj = response.get("expires_in");
            if (expiresObj == null) {
                expiresObj = response.get("expiresIn");
            }
            if (expiresObj != null) {
                try {
                    expiresInSec = Long.parseLong(String.valueOf(expiresObj));
                } catch (NumberFormatException ignored) {
                    // 保留默认值，避免解析失败中断流程
                }
            }

            cachedAccessToken = token;
            accessTokenExpireAtMs = now + expiresInSec * 1000L;
            return token;
        } catch (Exception e) {
            log.warn("【QQ鉴权】通过 appId/appSecret 获取 access_token 失败，将回退到本地 token 配置", e);
            return null;
        }
    }

    /**
     * 构建兼容模式下的 Authorization 值。
     */
    private String buildLegacyAuthorizationToken() {
        String appId = qqProperties.getAppId();
        String rawToken = qqProperties.getToken();
        if (rawToken == null || rawToken.isBlank()) {
            return "QQBot " + appId + ".";
        }
        String token = rawToken.trim();
        if (token.startsWith("QQBot ") || token.startsWith("Bot ")) {
            return token;
        }
        if (appId != null && !appId.isBlank()) {
            if (token.startsWith(appId + ":")) {
                return "QQBot " + token;
            }
            if (token.startsWith(appId + ".")) {
                return "QQBot " + token;
            }
            return "QQBot " + appId + "." + token;
        }
        return "QQBot " + token;
    }
}
