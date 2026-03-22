package com.example.interview.im.model;

import lombok.Builder;
import lombok.Data;

/**
 * 统一回复模型 (UnifiedReply)
 * 
 * 用于封装各业务 Agent 产出的处理结果，将其转化为可发送回 IM 渠道的消息格式。
 */
@Data
@Builder
public class UnifiedReply {
    /** 目标平台，如 "feishu" */
    private String platform;
    /** 系统内部会话隔离 ID */
    private String sessionId;
    /** 引用回复的消息 ID（如果有） */
    private String replyToMessageId;
    /** 纯文本回复内容 */
    private String text;
    /** 可选的富文本或卡片消息负载 (JSON 字符串) */
    private String cardPayload; 
    /** 链路追踪 ID，用于排查日志 */
    private String traceId;
}
