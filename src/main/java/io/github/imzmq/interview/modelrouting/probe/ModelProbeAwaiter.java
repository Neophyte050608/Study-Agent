package io.github.imzmq.interview.modelrouting.probe;

import io.github.imzmq.interview.common.api.BusinessException;
import io.github.imzmq.interview.common.api.ErrorCode;
import io.github.imzmq.interview.modelrouting.core.ModelRoutingProperties;
import io.github.imzmq.interview.modelrouting.core.TimeoutHint;
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
                                        new BusinessException(ErrorCode.MODEL_STREAM_EMPTY, "未收到任何Token"));
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
            throw new BusinessException(ErrorCode.MODEL_PROBE_TIMEOUT, "首Token探测超时（" + firstTokenMs + "ms）", e);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.MODEL_PROBE_FAILED, "首Token探测失败", e);
        }

        try {
            String result = resultFuture.get(totalMs, TimeUnit.MILLISECONDS);
            if (result == null || result.trim().isEmpty()) {
                throw new BusinessException(ErrorCode.MODEL_RESPONSE_EMPTY);
            }
            return result.trim();
        } catch (TimeoutException e) {
            throw new BusinessException(ErrorCode.MODEL_RESPONSE_TIMEOUT, "总响应超时（" + totalMs + "ms）", e);
        } catch (Exception e) {
            if (e.getCause() instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException(ErrorCode.MODEL_CALL_FAILED, e);
        }
    }
}



