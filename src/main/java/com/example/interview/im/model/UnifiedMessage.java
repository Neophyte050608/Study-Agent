package com.example.interview.im.model;

import lombok.Builder;
import lombok.Data;

/**
 * 统一消息模型 (UnifiedMessage)
 * 
 * 用于封装从各 IM 渠道（如飞书）接收到的原始消息，并将其标准化为系统可处理的通用格式。
 * 这层抽象使得后续业务逻辑无需关心消息是来自飞书、企业微信还是 Web 渠道。
 */
@Data
@Builder
public class UnifiedMessage {
    /** 消息来源平台，如 "feishu" */
    private String platform;
    /** 应用 ID */
    private String appId;
    /** 事件唯一 ID，用于消息去重 */
    private String eventId;
    /** 原始消息 ID，用于引用回复 */
    private String messageId;
    /** 会话 ID（群组 ID 或个人 ID） */
    private String chatId;
    /** 发送者 ID（用户 ID） */
    private String senderId;
    /** 系统内部会话隔离 ID，通常由 chatId + senderId 组合而成 */
    private String sessionId;
    /** 会话类型，如 "p2p" (单聊) 或 "group" (群聊) */
    private String chatType;
    /** 消息内容类型，目前主要支持 "text" */
    private String contentType;
    /** 清洗后的纯文本消息内容 */
    private String content;
    /** 是否在群聊中明确提到了机器人 */
    private boolean mentionsBot;
    /** 消息发送的时间戳 */
    private long timestamp;

    /**
     * 显式 Getter：避免在 Lombok 注解处理异常时影响编译。
     */
    public String getEventId() {
        return eventId;
    }
}
