package com.example.interview.modelrouting;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 首包探测器。
 * 专门用于解决大模型调用时的“首包假死”问题（TCP连接建立成功，但模型长时间不返回第一个Token）。
 */
@Component
public class ModelProbeAwaiter {

    private final ModelRoutingProperties properties;
    private final Executor executor;

    public ModelProbeAwaiter(ModelRoutingProperties properties,
                             @Qualifier("ragRetrieveExecutor") Executor executor) {
        this.properties = properties;
        this.executor = executor;
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

    public String awaitFirstToken(Flux<String> tokenFlux, TimeoutHint hint) {
        long firstTokenMs = hint != null ? hint.getFirstTokenTimeoutMs()
                : Math.max(500L, properties.getStream().getFirstPacketTimeoutMs());
        long totalMs = hint != null ? hint.getTotalResponseTimeoutMs()
                : Math.max(firstTokenMs * 3, properties.getStream().getTotalResponseTimeoutMs());

        CompletableFuture<Void> firstTokenSignal = new CompletableFuture<>();
        AtomicBoolean firstTokenReceived = new AtomicBoolean(false);
        StringBuilder fullResponse = new StringBuilder();

        CompletableFuture<String> resultFuture = CompletableFuture.supplyAsync(() -> {
            try {
                tokenFlux
                        .doOnNext(token -> {
                            if (token != null && !token.isEmpty()) {
                                fullResponse.append(token);
                                if (firstTokenReceived.compareAndSet(false, true)) {
                                    firstTokenSignal.complete(null);
                                }
                            }
                        })
                        .doOnError(firstTokenSignal::completeExceptionally)
                        .doOnComplete(() -> {
                            if (!firstTokenReceived.get()) {
                                firstTokenSignal.completeExceptionally(
                                        new ModelRoutingException("流式返回为空，未收到任何Token"));
                            }
                        })
                        .blockLast(Duration.ofMillis(totalMs));
            } catch (Exception e) {
                if (!firstTokenReceived.get()) {
                    firstTokenSignal.completeExceptionally(e);
                }
                throw e instanceof RuntimeException runtimeException
                        ? runtimeException
                        : new RuntimeException(e);
            }
            return fullResponse.toString();
        }, executor);

        try {
            firstTokenSignal.get(firstTokenMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            resultFuture.cancel(true);
            throw new ModelRoutingException("首Token探测超时（" + firstTokenMs + "ms）", e);
        } catch (Exception e) {
            throw new ModelRoutingException("首Token探测失败", e);
        }

        try {
            String result = resultFuture.get(totalMs, TimeUnit.MILLISECONDS);
            if (result == null || result.trim().isEmpty()) {
                throw new ModelRoutingException("响应内容为空");
            }
            return result.trim();
        } catch (TimeoutException e) {
            throw new ModelRoutingException("总响应超时（" + totalMs + "ms）", e);
        } catch (Exception e) {
            if (e.getCause() instanceof ModelRoutingException modelRoutingException) {
                throw modelRoutingException;
            }
            throw new ModelRoutingException("模型调用失败", e);
        }
    }
}
