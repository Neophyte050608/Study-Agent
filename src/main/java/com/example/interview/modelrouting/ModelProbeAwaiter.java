package com.example.interview.modelrouting;

import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 首包探测器。
 * 专门用于解决大模型调用时的“首包假死”问题（TCP连接建立成功，但模型长时间不返回第一个Token）。
 */
@Component
public class ModelProbeAwaiter {

    private final ModelRoutingProperties properties;

    public ModelProbeAwaiter(ModelRoutingProperties properties) {
        this.properties = properties;
    }

    /**
     * 阻塞等待模型调用的异步结果，并执行超时与内容有效性校验。
     *
     * @param firstPacketFuture 异步执行的模型调用任务
     * @return 校验通过后的模型响应文本
     * @throws ModelRoutingException 当探测超时、返回为空或内容长度不足时抛出
     */
    public String awaitFirstPacket(CompletableFuture<String> firstPacketFuture) {
        try {
            String packet = firstPacketFuture.get(Math.max(500L, properties.getStream().getFirstPacketTimeoutMs()), TimeUnit.MILLISECONDS);
            if (packet == null) {
                throw new ModelRoutingException("首包为空");
            }
            String normalized = packet.trim();
            if (normalized.length() < Math.max(1, properties.getStream().getFirstPacketMinChars())) {
                throw new ModelRoutingException("首包长度不足");
            }
            return normalized;
        } catch (Exception ex) {
            throw new ModelRoutingException("首包探测失败", ex);
        }
    }
}
