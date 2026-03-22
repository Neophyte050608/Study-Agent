package com.example.interview.im.model;

/**
 * 统一消息模型 (UnifiedMessage)
 * 
 * 用于封装从各 IM 渠道（如飞书）接收到的原始消息，并将其标准化为系统可处理的通用格式。
 * 这层抽象使得后续业务逻辑无需关心消息是来自飞书、企业微信还是 Web 渠道。
 */
public class UnifiedMessage {
    private String platform;
    private String appId;
    private String eventId;
    private String messageId;
    private String chatId;
    private String senderId;
    private String sessionId;
    private String chatType;
    private String contentType;
    private String content;
    private boolean mentionsBot;
    private long timestamp;

    public UnifiedMessage() {}

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getChatId() { return chatId; }
    public void setChatId(String chatId) { this.chatId = chatId; }
    public String getSenderId() { return senderId; }
    public void setSenderId(String senderId) { this.senderId = senderId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getChatType() { return chatType; }
    public void setChatType(String chatType) { this.chatType = chatType; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public boolean isMentionsBot() { return mentionsBot; }
    public void setMentionsBot(boolean mentionsBot) { this.mentionsBot = mentionsBot; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final UnifiedMessage msg = new UnifiedMessage();
        public Builder platform(String p) { msg.setPlatform(p); return this; }
        public Builder appId(String a) { msg.setAppId(a); return this; }
        public Builder eventId(String e) { msg.setEventId(e); return this; }
        public Builder messageId(String m) { msg.setMessageId(m); return this; }
        public Builder chatId(String c) { msg.setChatId(c); return this; }
        public Builder senderId(String s) { msg.setSenderId(s); return this; }
        public Builder sessionId(String s) { msg.setSessionId(s); return this; }
        public Builder chatType(String c) { msg.setChatType(c); return this; }
        public Builder contentType(String c) { msg.setContentType(c); return this; }
        public Builder content(String c) { msg.setContent(c); return this; }
        public Builder mentionsBot(boolean m) { msg.setMentionsBot(m); return this; }
        public Builder timestamp(long t) { msg.setTimestamp(t); return this; }
        public UnifiedMessage build() { return msg; }
    }
}
