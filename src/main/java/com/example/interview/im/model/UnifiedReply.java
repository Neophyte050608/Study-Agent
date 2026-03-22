package com.example.interview.im.model;

/**
 * 统一回复模型 (UnifiedReply)
 * 
 * 用于封装各业务 Agent 产出的处理结果，将其转化为可发送回 IM 渠道的消息格式。
 */
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

    public UnifiedReply() {}

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getReplyToMessageId() { return replyToMessageId; }
    public void setReplyToMessageId(String replyToMessageId) { this.replyToMessageId = replyToMessageId; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getCardPayload() { return cardPayload; }
    public void setCardPayload(String cardPayload) { this.cardPayload = cardPayload; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final UnifiedReply msg = new UnifiedReply();
        public Builder platform(String p) { msg.setPlatform(p); return this; }
        public Builder sessionId(String s) { msg.setSessionId(s); return this; }
        public Builder replyToMessageId(String r) { msg.setReplyToMessageId(r); return this; }
        public Builder text(String t) { msg.setText(t); return this; }
        public Builder cardPayload(String c) { msg.setCardPayload(c); return this; }
        public Builder traceId(String t) { msg.setTraceId(t); return this; }
        public UnifiedReply build() { return msg; }
    }
}
