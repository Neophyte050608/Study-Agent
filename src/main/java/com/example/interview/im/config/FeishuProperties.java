package com.example.interview.im.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "im.feishu")
public class FeishuProperties {
    private String appId;
    private String appSecret;
    private String encryptKey;
    private String verificationToken;
    private String botName;
    private boolean useWs; // 是否开启长连接模式 (WebSocket)

    public String getAppId() {
        return appId;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public String getEncryptKey() {
        return encryptKey;
    }

    public String getVerificationToken() {
        return verificationToken;
    }

    public String getBotName() {
        return botName;
    }

    public boolean isUseWs() {
        return useWs;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
    }

    public void setEncryptKey(String encryptKey) {
        this.encryptKey = encryptKey;
    }

    public void setVerificationToken(String verificationToken) {
        this.verificationToken = verificationToken;
    }

    public void setBotName(String botName) {
        this.botName = botName;
    }

    public void setUseWs(boolean useWs) {
        this.useWs = useWs;
    }
}
