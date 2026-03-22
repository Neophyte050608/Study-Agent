package com.example.interview.im.service;

import com.example.interview.im.config.FeishuProperties;
import com.example.interview.im.model.UnifiedReply;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.ReplyMessageReq;
import com.lark.oapi.service.im.v1.model.ReplyMessageReqBody;
import com.lark.oapi.service.im.v1.model.ReplyMessageResp;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 飞书消息回复适配器 (FeishuReplyAdapter)
 * 负责将统一的消息回复模型转换为飞书特定的 API 调用。
 */
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class FeishuReplyAdapter {

    private static final Logger log = LoggerFactory.getLogger(FeishuReplyAdapter.class);

    private final FeishuProperties feishuProperties;
    private final ObjectMapper objectMapper;
    private Client client;

    public FeishuReplyAdapter(FeishuProperties feishuProperties, ObjectMapper objectMapper) {
        this.feishuProperties = feishuProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 初始化飞书客户端
     */
    @PostConstruct
    public void init() {
        if (feishuProperties.getAppId() != null && feishuProperties.getAppSecret() != null) {
            // 构建飞书开放平台客户端
            this.client = Client.newBuilder(feishuProperties.getAppId(), feishuProperties.getAppSecret()).build();
            log.info("飞书客户端初始化成功，AppID: {}", feishuProperties.getAppId());
        } else {
            log.warn("飞书 AppId 或 AppSecret 缺失，无法初始化飞书客户端。");
        }
    }

    /**
     * 发送回复消息
     * 
     * @param reply 统一回复模型
     * @return 是否发送成功
     */
    public boolean sendReply(UnifiedReply reply) {
        if (this.client == null) {
            log.error("飞书客户端未初始化。");
            return false;
        }

        try {
            // 目前仅支持针对特定消息的引用回复 (Reply Message)
            if (reply.getReplyToMessageId() != null && !reply.getReplyToMessageId().isEmpty()) {
                // 构造回复请求体，类型为纯文本
                ReplyMessageReqBody body = ReplyMessageReqBody.newBuilder()
                        .msgType("text")
                        .content(buildTextContent(reply.getText()))
                        .build();

                // 构造回复请求，指定原始消息 ID
                ReplyMessageReq req = ReplyMessageReq.newBuilder()
                        .messageId(reply.getReplyToMessageId())
                        .replyMessageReqBody(body)
                        .build();

                // 执行回复 API 调用
                ReplyMessageResp resp = client.im().message().reply(req);
                if (!resp.success()) {
                    log.error("飞书消息回复失败。错误码: {}, 错误信息: {}", resp.getCode(), resp.getMsg());
                    return false;
                }
                return true;
            } else {
                log.warn("缺少 replyToMessageId，无法进行引用回复。");
                return false;
            }
        } catch (Exception e) {
            log.error("调用飞书 API 发送回复时发生异常", e);
            return false;
        }
    }

    /**
     * 构造飞书要求的文本内容 JSON 格式
     */
    private String buildTextContent(String text) {
        try {
            Map<String, String> contentMap = new HashMap<>();
            contentMap.put("text", text);
            // 使用 Jackson ObjectMapper 序列化，确保与 Spring 保持一致
            return objectMapper.writeValueAsString(contentMap);
        } catch (Exception e) {
            log.error("JSON 序列化失败", e);
            return "{\"text\":\"[内容发送失败]\"}";
        }
    }
}
