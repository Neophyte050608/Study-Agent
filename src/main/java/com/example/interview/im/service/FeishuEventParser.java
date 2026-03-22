package com.example.interview.im.service;

import com.example.interview.im.config.FeishuProperties;
import com.example.interview.im.model.UnifiedMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 飞书事件解析器 (FeishuEventParser)
 * 
 * 核心职责：将飞书开放平台推送到 Webhook 或长连接的消息 JSON 格式化为内部统一的 UnifiedMessage 模型。
 * 包含对消息内容的清洗（如去除 @ 机器人占位符）、群聊过滤逻辑以及签名安全校验。
 */
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class FeishuEventParser {

    private static final Logger log = LoggerFactory.getLogger(FeishuEventParser.class);

    private final FeishuProperties feishuProperties;
    private final ObjectMapper objectMapper;

    public FeishuEventParser(FeishuProperties feishuProperties, ObjectMapper objectMapper) {
        this.feishuProperties = feishuProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 将标准的 im.message.receive_v1 事件 JSON 解析为 UnifiedMessage 模型。
     * 适用于 Webhook 模式接收到的原始 JSON 字符串。
     * 
     * @param rootNode 飞书推送的原始 JSON 根节点
     * @param appId    应用 ID
     * @return 标准化的统一消息模型，如果不是文本消息或不满足处理条件则返回 null
     */
    public UnifiedMessage parseMessageEvent(JsonNode rootNode, String appId) {
        try {
            // 获取消息头和核心事件体
            JsonNode header = rootNode.get("header");
            JsonNode event = rootNode.get("event");
            
            if (header == null || event == null) {
                log.warn("【飞书解析】事件数据不完整，缺少 header 或 event 节点");
                return null;
            }

            JsonNode message = event.get("message");
            JsonNode sender = event.get("sender");
            
            if (message == null || sender == null) {
                log.warn("【飞书解析】事件数据不完整，缺少 message 或 sender 节点");
                return null;
            }

            // 兼容处理：SDK 序列化后可能是驼峰 (eventId)，Webhook 推送原始 JSON 是下划线 (event_id)
            String eventId = getField(header, "event_id", "eventId");
            String timestampStr = getField(header, "create_time", "createTime");
            long timestamp = (timestampStr == null || timestampStr.isEmpty()) ? System.currentTimeMillis() : Long.parseLong(timestampStr);

            // 目前仅支持解析文本类型的消息
            String messageType = getField(message, "message_type", "messageType");
            if (!"text".equals(messageType)) {
                log.info("【飞书解析】忽略非文本类型的消息，类型为: {}", messageType);
                return null;
            }

            String messageId = getField(message, "message_id", "messageId");
            String chatId = getField(message, "chat_id", "chatId");
            String chatType = getField(message, "chat_type", "chatType");
            
            log.info("【飞书解析】开始处理消息: id={}, type={}, chatType={}", messageId, messageType, chatType);

            // 优先获取 user_id，如果缺失则回退到 open_id
            String senderId = "unknown";
            JsonNode senderIdNode = sender.has("sender_id") ? sender.get("sender_id") : sender.get("senderId");
            if (senderIdNode != null) {
                if (senderIdNode.has("user_id") && !senderIdNode.get("user_id").isNull()) {
                    senderId = senderIdNode.get("user_id").asText();
                } else if (senderIdNode.has("userId") && !senderIdNode.get("userId").isNull()) {
                    senderId = senderIdNode.get("userId").asText();
                } else if (senderIdNode.has("open_id") && !senderIdNode.get("open_id").isNull()) {
                    senderId = senderIdNode.get("open_id").asText();
                } else if (senderIdNode.has("openId") && !senderIdNode.get("openId").isNull()) {
                    senderId = senderIdNode.get("openId").asText();
                }
            }

            // 解析内容 JSON
            String contentJson = message.get("content").asText();
            JsonNode contentNode = objectMapper.readTree(contentJson);
            String rawText = contentNode.get("text").asText();

            // 检查群聊中是否提到了机器人
            boolean mentionsBot = false;
            if (message.has("mentions")) {
                for (JsonNode mention : message.get("mentions")) {
                    if (mention.has("name") && feishuProperties.getBotName().equals(mention.get("name").asText())) {
                        mentionsBot = true;
                        break;
                    }
                }
            }

            // 群聊过滤策略
            if ("group".equals(chatType) && !mentionsBot) {
                log.debug("【飞书解析】忽略未 @ 机器人的群组消息: {}", eventId);
                return null;
            }

            // 清洗消息文本
            String cleanText = rawText.replaceAll("@_user_\\d+", "").trim();
            if (feishuProperties.getBotName() != null) {
                cleanText = cleanText.replace("@" + feishuProperties.getBotName(), "").trim();
            }

            // 会话隔离标识
            String sessionId = chatId + senderId;

            return UnifiedMessage.builder()
                    .platform("feishu")
                    .appId(appId)
                    .eventId(eventId)
                    .messageId(messageId)
                    .chatId(chatId)
                    .senderId(senderId)
                    .sessionId(sessionId)
                    .chatType(chatType)
                    .contentType(messageType)
                    .content(cleanText)
                    .mentionsBot(mentionsBot)
                    .timestamp(timestamp)
                    .build();

        } catch (Exception e) {
            log.error("【飞书解析】解析消息事件过程中发生异常", e);
            return null;
        }
    }

    /**
     * 辅助方法：从 JsonNode 中尝试获取多个可能的字段名（下划线 vs 驼峰）
     */
    private String getField(JsonNode node, String... keys) {
        if (node == null) return null;
        for (String key : keys) {
            if (node.has(key)) {
                return node.get(key).asText();
            }
        }
        return null;
    }

    /**
     * 解析飞书长连接 (WS) 模式推送的消息事件。
     * 长连接模式下，SDK 可能已经对数据进行了分层封装，这里提供兼容性处理。
     * 
     * @param rootNode 经序列化后的事件 JSON 节点
     * @param appId    应用 ID
     * @return 标准化的统一消息模型
     */
    public UnifiedMessage parseMessageEventFromWs(JsonNode rootNode, String appId) {
        try {
            // 如果 WS 推送的数据包含标准 Webhook 结构 (header + event)
            if (rootNode.has("header") && rootNode.has("event")) {
                return parseMessageEvent(rootNode, appId);
            }
            
            // 处理直接封装在根层级的事件结构
            JsonNode message = rootNode.get("message");
            JsonNode sender = rootNode.get("sender");
            
            // 长连接模式如果缺失 header，生成唯一追踪标识
            String eventId = "ws-" + System.currentTimeMillis(); 
            long timestamp = System.currentTimeMillis();

            String messageType = message.get("message_type").asText();
            if (!"text".equals(messageType)) {
                return null;
            }

            String messageId = message.get("message_id").asText();
            String chatId = message.get("chat_id").asText();
            String chatType = message.get("chat_type").asText();

            String senderId = "unknown";
            if (sender.has("sender_id") && sender.get("sender_id").has("user_id")) {
                senderId = sender.get("sender_id").get("user_id").asText();
            }

            String contentJson = message.get("content").asText();
            JsonNode contentNode = objectMapper.readTree(contentJson);
            String rawText = contentNode.get("text").asText();

            boolean mentionsBot = false;
            if (message.has("mentions")) {
                for (JsonNode mention : message.get("mentions")) {
                    if (mention.has("name") && feishuProperties.getBotName().equals(mention.get("name").asText())) {
                        mentionsBot = true;
                        break;
                    }
                }
            }

            if ("group".equals(chatType) && !mentionsBot) {
                return null;
            }

            String cleanText = rawText.replaceAll("@_user_\\d+", "").trim();
            if (feishuProperties.getBotName() != null) {
                cleanText = cleanText.replace("@" + feishuProperties.getBotName(), "").trim();
            }

            String sessionId = chatId + senderId;

            return UnifiedMessage.builder()
                    .platform("feishu")
                    .appId(appId)
                    .eventId(eventId)
                    .messageId(messageId)
                    .chatId(chatId)
                    .senderId(senderId)
                    .sessionId(sessionId)
                    .chatType(chatType)
                    .contentType(messageType)
                    .content(cleanText)
                    .mentionsBot(mentionsBot)
                    .timestamp(timestamp)
                    .build();

        } catch (Exception e) {
            log.error("【飞书解析】从长连接模式解析消息事件失败", e);
            return null;
        }
    }

    /**
     * 校验飞书事件推送的签名安全性。
     * 计算规则：sha256(timestamp + nonce + encrypt_key + body)
     * 
     * @param signature  飞书传递的签名摘要
     * @param timestamp  飞书传递的时间戳
     * @param nonce      飞书传递的随机串
     * @param body       原始 HTTP 请求体内容
     * @param encryptKey 开发者后台配置的加密密钥
     * @return 是否校验通过
     */
    public boolean verifySignature(String signature, String timestamp, String nonce, String body, String encryptKey) {
        // 如果未配置密钥，则跳过校验（仅用于测试环境，不推荐生产使用）
        if (encryptKey == null || encryptKey.isEmpty()) {
            log.warn("【飞书安全】未配置 encryptKey，跳过签名验证。");
            return true;
        }

        if (signature == null || timestamp == null || nonce == null) {
            return false;
        }

        try {
            // 按照官方算法进行拼接
            String str = timestamp + nonce + encryptKey + body;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(str.getBytes(StandardCharsets.UTF_8));

            // 将二进制摘要转为十六进制字符串
            StringBuilder hexString = new StringBuilder(2 * hash.length);
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            String calculatedSignature = hexString.toString();
            return calculatedSignature.equals(signature);
        } catch (NoSuchAlgorithmException e) {
            log.error("【飞书安全】SHA-256 算法组件缺失", e);
            return false;
        }
    }
}
