package io.github.imzmq.interview.agent.a2a;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * RocketMQ 版 A2A 总线实现（带内存总线降级）。
 *
 * <p>语义：</p>
 * <ul>
 *   <li>发布：优先发送到 RocketMQ；发送/序列化失败时回退到 fallbackBus（可能导致重复投递，但避免完全丢失）。</li>
 *   <li>订阅与 requestReply：由 fallbackBus 承担（本实现只负责“对外发布/入站转发”）。</li>
 * </ul>
 */
public class RocketMqA2ABus implements A2ABus {

    private static final Logger logger = LoggerFactory.getLogger(RocketMqA2ABus.class);

    private final A2ABus fallbackBus;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final int maxPublishRetries;
    private final long retryBackoffMillis;

    public RocketMqA2ABus(
            A2ABus fallbackBus,
            RocketMQTemplate rocketMQTemplate,
            ObjectMapper objectMapper,
            String topic,
            int maxPublishRetries,
            long retryBackoffMillis
    ) {
        this.fallbackBus = fallbackBus;
        this.rocketMQTemplate = rocketMQTemplate;
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
        this.topic = topic;
        this.maxPublishRetries = Math.max(0, maxPublishRetries);
        this.retryBackoffMillis = Math.max(0L, retryBackoffMillis);
    }

    @Override
    public void publish(A2AMessage message) {
        if (message == null) {
            return;
        }
        if (rocketMQTemplate == null) {
            // 中间件不可用时直接回退：确保功能可用性优先，其次再追求可靠性。
            logger.warn("RocketMQTemplate 不可用，A2A 消息回退内存总线: receiver={}, status={}", message.receiver(), message.status());
            fallbackBus.publish(message);
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(message);
            RuntimeException lastError = null;
            for (int attempt = 0; attempt <= maxPublishRetries; attempt++) {
                try {
                    rocketMQTemplate.convertAndSend(topic, payload);
                    return;
                } catch (RuntimeException e) {
                    lastError = e;
                    if (attempt < maxPublishRetries && retryBackoffMillis > 0) {
                        // 简单退避：避免瞬时抖动导致的快速重试风暴。
                        try {
                            Thread.sleep(retryBackoffMillis);
                        } catch (InterruptedException interruptedException) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
            if (lastError != null) {
                throw lastError;
            }
        } catch (JsonProcessingException e) {
            logger.warn("A2A 消息序列化失败，回退内存总线。原因: {}", e.getMessage());
            fallbackBus.publish(message);
        } catch (RuntimeException e) {
            logger.warn("RocketMQ 发送失败，回退内存总线。原因: {}", e.getMessage());
            fallbackBus.publish(message);
        }
    }

    @Override
    public void subscribe(String receiver, Consumer<A2AMessage> handler) {
        fallbackBus.subscribe(receiver, handler);
    }

    @Override
    public Optional<A2AMessage> requestReply(A2AMessage message) {
        return fallbackBus.requestReply(message);
    }

    public boolean dispatchInbound(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return false;
        }
        try {
            A2AMessage message = objectMapper.readValue(rawMessage, A2AMessage.class);
            // 入站只做反序列化 + 转发：订阅投递/幂等由 fallbackBus（通常是 InMemoryA2ABus）统一处理。
            fallbackBus.publish(message);
            return true;
        } catch (JsonProcessingException e) {
            logger.warn("A2A 入站消息解析失败: {}", e.getMessage());
            return false;
        }
    }
}

