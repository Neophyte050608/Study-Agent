package com.example.interview.im.service;

import com.example.interview.im.model.UnifiedReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

/**
 * QQ 回复适配器
 * 负责将统一的回复模型转换为 QQ OpenAPI 格式并发送
 */
@Service
public class QqReplyAdapter {

    private static final Logger log = LoggerFactory.getLogger(QqReplyAdapter.class);
    
    private final QqAuthTokenProvider qqAuthTokenProvider;
    private final RestClient restClient;
    
    // 沙箱环境 OpenAPI 地址
    private static final String API_BASE_URL = "https://sandbox.api.sgroup.qq.com";

    public QqReplyAdapter(QqAuthTokenProvider qqAuthTokenProvider, RestClient.Builder restClientBuilder) {
        this.qqAuthTokenProvider = qqAuthTokenProvider;
        this.restClient = restClientBuilder.baseUrl(API_BASE_URL).build();
    }

    /**
     * 发送回复到 QQ 渠道
     */
    public void sendReply(UnifiedReply reply) {
        try {
            ParsedSession parsedSession = parseSession(reply.getSessionId());
            if (parsedSession == null) {
                log.error("【QQ回复】无效的 SessionId: {}", reply.getSessionId());
                return;
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("content", reply.getText());
            payload.put("msg_type", 0);
            // 如果需要回复特定消息，可以带上 msg_id
            if (reply.getReplyToMessageId() != null) {
                payload.put("msg_id", reply.getReplyToMessageId());
            }

            String token = qqAuthTokenProvider.resolveAuthorizationHeader();

            if ("p2p".equals(parsedSession.chatType())) {
                restClient.post()
                        .uri("/v2/users/{openid}/messages", parsedSession.senderId())
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .toBodilessEntity();
            } else if (parsedSession.chatId().startsWith("group_openid:")) {
                String groupOpenid = parsedSession.chatId().substring("group_openid:".length());
                restClient.post()
                        .uri("/v2/groups/{group_openid}/messages", groupOpenid)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .toBodilessEntity();
            } else {
                restClient.post()
                        .uri("/channels/{channel_id}/messages", parsedSession.chatId())
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .toBodilessEntity();
            }

            log.info("【QQ回复】成功发送消息到 chatId: {}, chatType: {}", parsedSession.chatId(), parsedSession.chatType());
        } catch (Exception e) {
            log.error("【QQ回复】发送消息失败", e);
        }
    }

    private ParsedSession parseSession(String sessionId) {
        if (sessionId == null || !sessionId.startsWith("qq_")) {
            return null;
        }
        String raw = sessionId.substring(3);
        int lastUnderscore = raw.lastIndexOf('_');
        if (lastUnderscore <= 0 || lastUnderscore >= raw.length() - 1) {
            return null;
        }
        String chatId = raw.substring(0, lastUnderscore);
        String senderId = raw.substring(lastUnderscore + 1);
        String chatType = "group";
        if ("private".equals(chatId) || chatId.equals(senderId)) {
            chatType = "p2p";
        }
        return new ParsedSession(chatId, senderId, chatType);
    }

    private record ParsedSession(String chatId, String senderId, String chatType) {
    }
}
