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

    /**
     * 显式 Getter：避免在 Lombok 注解处理异常时影响编译。
     */
    public String getAppId() {
        return appId;
    }

    /**
     * 显式 Getter：避免在 Lombok 注解处理异常时影响编译。
     */
    public String getAppSecret() {
        return appSecret;
    }

    /**
     * 显式 Getter：避免在 Lombok 注解处理异常时影响编译。
     */
    public String getEncryptKey() {
        return encryptKey;
    }

    /**
     * 显式 Getter：避免在 Lombok 注解处理异常时影响编译。
     */
    public String getVerificationToken() {
        return verificationToken;
    }

    /**
     * 显式 Getter：避免在 Lombok 注解处理异常时影响编译。
     */
    public String getBotName() {
        return botName;
    }

    /**
     * 显式 Getter：避免在 Lombok 注解处理异常时影响编译。
     */
    public boolean isUseWs() {
        return useWs;
    }

    /**
     * 显式 Setter：保证 @ConfigurationProperties 在 Lombok 失效时仍可绑定配置。
     */
    public void setAppId(String appId) {
        this.appId = appId;
    }

    /**
     * 显式 Setter：保证 @ConfigurationProperties 在 Lombok 失效时仍可绑定配置。
     */
    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
    }

    /**
     * 显式 Setter：保证 @ConfigurationProperties 在 Lombok 失效时仍可绑定配置。
     */
    public void setEncryptKey(String encryptKey) {
        this.encryptKey = encryptKey;
    }

    /**
     * 显式 Setter：保证 @ConfigurationProperties 在 Lombok 失效时仍可绑定配置。
     */
    public void setVerificationToken(String verificationToken) {
        this.verificationToken = verificationToken;
    }

    /**
     * 显式 Setter：保证 @ConfigurationProperties 在 Lombok 失效时仍可绑定配置。
     */
    public void setBotName(String botName) {
        this.botName = botName;
    }

    /**
     * 显式 Setter：保证 @ConfigurationProperties 在 Lombok 失效时仍可绑定配置。
     */
    public void setUseWs(boolean useWs) {
        this.useWs = useWs;
    }
}
