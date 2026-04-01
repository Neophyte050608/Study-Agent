package com.example.interview.agent.a2a;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.UUID;

/**
 * 内存版 A2A 总线实现。
 *
 * <p>特点：</p>
 * <ul>
 *   <li>同进程内发布/订阅：用于本地开发、单体部署或作为消息中间件不可用时的降级兜底。</li>
 *   <li>幂等过滤：通过 A2AIdempotencyStore 对 messageId 去重，减少重复投递带来的副作用。</li>
 *   <li>请求-应答：当 intent=RETURN_RESULT 且携带 correlationId 时，用 pendingReplies 完成“按关联ID唤醒”。</li>
 * </ul>
 */
public class InMemoryA2ABus implements A2ABus {

    private static final Logger logger = LoggerFactory.getLogger(InMemoryA2ABus.class);

    private final Map<String, List<Consumer<A2AMessage>>> subscribers = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<A2AMessage>> pendingReplies = new ConcurrentHashMap<>();
    private final A2AIdempotencyStore idempotencyStore;

    public InMemoryA2ABus(A2AIdempotencyStore idempotencyStore) {
        this.idempotencyStore = idempotencyStore;
    }

    @Override
    public void publish(A2AMessage message) {
        if (message == null || message.receiver() == null || message.receiver().isBlank()) {
            return;
        }

        // 先做幂等过滤：避免重复投递导致重复计数、重复落库等副作用。
        if (!idempotencyStore.shouldProcess(message)) {
            return;
        }
        String correlationId = message.correlationId();
        if (message.intent() == A2AIntent.RETURN_RESULT && correlationId != null && !correlationId.isBlank()) {
            // requestReply 场景：按 correlationId 取出等待中的 future 并完成。
            CompletableFuture<A2AMessage> future = pendingReplies.remove(correlationId);
            if (future != null) {
                future.complete(message);
            }
        }

        // 同 receiver 的订阅先投递；再投递给 "*" 的通配订阅（常用于观测/审计）。
        List<Consumer<A2AMessage>> directHandlers = subscribers.getOrDefault(message.receiver(), List.of());
        directHandlers.forEach(handler -> invokeHandlerSafely("direct", message, handler));
        List<Consumer<A2AMessage>> wildcardHandlers = subscribers.getOrDefault("*", List.of());
        wildcardHandlers.forEach(handler -> invokeHandlerSafely("wildcard", message, handler));
    }

    @Override
    public void subscribe(String receiver, Consumer<A2AMessage> handler) {
        if (receiver == null || receiver.isBlank() || handler == null) {
            return;
        }
        subscribers.computeIfAbsent(receiver, key -> new CopyOnWriteArrayList<>()).add(handler);
    }

    @Override
    public java.util.Optional<A2AMessage> requestReply(A2AMessage message) {
        if (message == null) {
            return java.util.Optional.empty();
        }
        String correlationId = message.correlationId();
        if (correlationId == null || correlationId.isBlank()) {
            // 没有关联ID就无法做请求-应答；退化为普通 publish。
            publish(message);
            return java.util.Optional.empty();
        }

        // 允许通过 context 覆盖超时/重试/退避，便于不同调用方设置不同的交互体验。
        long baseTimeoutMs = readLong(message.context(), "replyTimeoutMs", 1200L);
        int retries = (int) readLong(message.context(), "replyRetries", 2L);
        long backoffMs = readLong(message.context(), "replyBackoffMs", 120L);
        CompletableFuture<A2AMessage> future = new CompletableFuture<>();
        pendingReplies.put(correlationId, future);
        try {
            for (int attempt = 0; attempt <= retries; attempt++) {
                // 重试时生成新的 messageId：避免被幂等去重误判为“已处理”导致永远等不到回包。
                A2AMessage outbound = attempt == 0 ? message : cloneForRetry(message);
                publish(outbound);

                // 超时采用指数退避：减少下游偶发慢响应时的无意义频繁重试。
                long timeoutMs = Math.max(200L, baseTimeoutMs * (1L << Math.min(attempt, 6)));
                try {
                    return java.util.Optional.ofNullable(future.get(timeoutMs, TimeUnit.MILLISECONDS));
                } catch (TimeoutException ignored) {
                    if (attempt < retries && backoffMs > 0) {
                        long sleep = Math.max(20L, backoffMs * (1L << Math.min(attempt, 6)));
                        try {
                            Thread.sleep(sleep);
                        } catch (InterruptedException interruptedException) {
                            Thread.currentThread().interrupt();
                            return java.util.Optional.empty();
                        }
                    }
                }
            }
            return java.util.Optional.empty();
        } catch (Exception ignored) {
            return java.util.Optional.empty();
        } finally {
            // 避免 pendingReplies 泄漏：无论成功/失败/异常都要清理。
            pendingReplies.remove(correlationId);
        }
    }

    private A2AMessage cloneForRetry(A2AMessage original) {
        return new A2AMessage(
                original.protocolVersion(),
                UUID.randomUUID().toString(),
                original.correlationId(),
                original.sender(),
                original.receiver(),
                original.replyTo(),
                original.intent(),
                original.payload(),
                original.context(),
                original.status(),
                original.error(),
                original.metadata(),
                original.trace(),
                original.createdAt()
        );
    }

    private long readLong(Map<String, Object> context, String key, long defaultValue) {
        if (context == null) {
            return defaultValue;
        }
        Object value = context.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private void invokeHandlerSafely(String type, A2AMessage message, Consumer<A2AMessage> handler) {
        try {
            handler.accept(message);
        } catch (Exception e) {
            logger.error(
                    "A2A {} handler failed. receiver={}, messageId={}, correlationId={}",
                    type,
                    message.receiver(),
                    message.messageId(),
                    message.correlationId(),
                    e
            );
        }
    }
}
