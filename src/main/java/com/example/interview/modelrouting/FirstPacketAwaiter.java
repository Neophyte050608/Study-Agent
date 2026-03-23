package com.example.interview.modelrouting;

import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
public class FirstPacketAwaiter {

    private final ModelRoutingProperties properties;

    public FirstPacketAwaiter(ModelRoutingProperties properties) {
        this.properties = properties;
    }

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
