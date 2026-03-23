package com.example.interview.im.service;

import com.example.interview.im.config.QqProperties;
import com.example.interview.im.model.UnifiedMessage;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * QQ 事件解析器
 * 负责将 QQ WebSocket 接收到的原始 JSON 转换为系统统一的 UnifiedMessage 模型。
 */
@Service
public class QqEventParser {

    private static final Logger log = LoggerFactory.getLogger(QqEventParser.class);
    private final QqProperties qqProperties;

    public QqEventParser(QqProperties qqProperties) {
        this.qqProperties = qqProperties;
    }

    /**
     * 将 QQ 消息事件解析为 UnifiedMessage
     * 
     * @param rootNode QQ推送的根节点
     * @return 标准化的统一消息模型，如果不符合处理条件则返回 null
     */
    public UnifiedMessage parseMessageEvent(JsonNode rootNode) {
        try {
            if (!rootNode.has("t") || rootNode.get("t").isNull()) {
                return null;
            }

            String eventType = rootNode.get("t").asText();
            JsonNode dataNode = rootNode.get("d");

            if (dataNode == null || dataNode.isNull()) {
                return null;
            }

            // 我们只处理文本相关的消息事件
            // DIRECT_MESSAGE_CREATE (私聊消息)
            // AT_MESSAGE_CREATE (频道群聊 @ 消息)
            // GROUP_AT_MESSAGE_CREATE (QQ群聊 @ 消息)
            // C2C_MESSAGE_CREATE (QQ单聊消息)
            boolean isPrivate = "DIRECT_MESSAGE_CREATE".equals(eventType) || "C2C_MESSAGE_CREATE".equals(eventType);
            boolean isGroupAt = "AT_MESSAGE_CREATE".equals(eventType) || "GROUP_AT_MESSAGE_CREATE".equals(eventType);
            boolean isGuildMessage = "MESSAGE_CREATE".equals(eventType);

            if (!isPrivate && !isGroupAt && !isGuildMessage) {
                log.debug("【QQ解析】忽略非消息事件: {}", eventType);
                return null; // 忽略其他事件
            }

            String messageId = dataNode.has("id") ? dataNode.get("id").asText() : "unknown";
            String eventId = rootNode.has("id") ? rootNode.get("id").asText(messageId) : messageId;
            
            // 提取发送者 ID
            String senderId = "unknown";
            if (dataNode.has("author") && dataNode.get("author").has("id")) {
                senderId = dataNode.get("author").get("id").asText();
            }

            // 提取群组或频道 ID (如果私聊，可以用 channel_id 或组合)
            String chatId = "private";
            if (dataNode.has("group_id")) {
                chatId = dataNode.get("group_id").asText();
            } else if (dataNode.has("group_openid")) {
                chatId = "group_openid:" + dataNode.get("group_openid").asText();
            } else if (dataNode.has("channel_id")) {
                chatId = dataNode.get("channel_id").asText();
            } else if (isPrivate && !"unknown".equals(senderId)) {
                chatId = senderId;
            }

            String rawContent = dataNode.has("content") ? dataNode.get("content").asText() : "";
            
            // 清洗内容 (去除 <@!botId> 或 <@botId> 占位符)
            String cleanContent = rawContent.replaceAll("<@!?[a-zA-Z0-9_-]+>", "").trim();
            
            String chatType = isPrivate ? "p2p" : "group";
            String sessionId = "qq_" + chatId + "_" + senderId;
            long timestamp = System.currentTimeMillis();

            return UnifiedMessage.builder()
                    .platform("qq")
                    .appId(qqProperties.getAppId())
                    .eventId(eventId)
                    .messageId(messageId)
                    .chatId(chatId)
                    .senderId(senderId)
                    .sessionId(sessionId)
                    .chatType(chatType)
                    .contentType("text")
                    .content(cleanContent)
                    .mentionsBot(isGroupAt)
                    .timestamp(timestamp)
                    .build();

        } catch (Exception e) {
            log.error("【QQ解析】解析消息事件过程中发生异常", e);
            return null;
        }
    }
}
