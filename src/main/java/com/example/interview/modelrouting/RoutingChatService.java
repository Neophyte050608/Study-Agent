package com.example.interview.modelrouting;

import com.example.interview.service.DynamicModelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import java.util.concurrent.Executor;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Service
public class RoutingChatService {

    private static final Logger logger = LoggerFactory.getLogger(RoutingChatService.class);

    private final ModelRoutingProperties properties;
    private final ModelSelector modelSelector;
    private final ModelRoutingExecutor modelRoutingExecutor;
    private final ModelHealthStore modelHealthStore;
    private final DynamicModelFactory dynamicModelFactory;
    private final FirstPacketAwaiter firstPacketAwaiter;
    private final ChatModel fallbackChatModel;
    private final AtomicLong routeFallbackCount = new AtomicLong(0);
    private final AtomicLong firstPacketTimeoutCount = new AtomicLong(0);
    private final AtomicLong firstPacketFailureCount = new AtomicLong(0);
    private final Executor ragRetrieveExecutor;

    public RoutingChatService(
            @Qualifier("ragRetrieveExecutor") Executor ragRetrieveExecutor,
            ModelRoutingProperties properties,
            ModelSelector modelSelector,
            ModelRoutingExecutor modelRoutingExecutor,
            ModelHealthStore modelHealthStore,
            DynamicModelFactory dynamicModelFactory,
            FirstPacketAwaiter firstPacketAwaiter,
            @Qualifier("openAiChatModel") ChatModel fallbackChatModel
    ) {
        this.properties = properties;
        this.modelSelector = modelSelector;
        this.modelRoutingExecutor = modelRoutingExecutor;
        this.modelHealthStore = modelHealthStore;
        this.dynamicModelFactory = dynamicModelFactory;
        this.firstPacketAwaiter = firstPacketAwaiter;
        this.ragRetrieveExecutor = ragRetrieveExecutor;
        this.fallbackChatModel = fallbackChatModel;
    }

    public String call(String prompt, ModelRouteType routeType, String stage) {
        if (!properties.isEnabled()) {
            return callWithModel(fallbackChatModel, prompt);
        }
        List<ModelRoutingCandidate> candidates = modelSelector.select(routeType);
        if (candidates.isEmpty()) {
            return callWithModel(fallbackChatModel, prompt);
        }
        return modelRoutingExecutor.execute(candidates, candidate -> {
            ChatModel chatModel = resolveChatModel(candidate);
            long start = System.currentTimeMillis();
            String response = callWithModel(chatModel, prompt);
            long cost = System.currentTimeMillis() - start;
            logger.info("模型路由命中: stage={}, candidate={}, provider={}, state={}, costMs={}",
                    stage,
                    candidate.name(),
                    candidate.provider(),
                    modelHealthStore.stateOf(candidate.name()),
                    cost);
            return response;
        }, stage);
    }

    /**
     * 带首包探测的模型调用。
     * 使用 CompletableFuture 异步发起请求，并结合 FirstPacketAwaiter 检查响应时间。
     * 如果某候选模型响应过慢（未在规定时间内返回首包），将抛出超时异常，触发熔断器状态转换并降级到下一个模型。
     */
    public String callWithFirstPacketProbe(String prompt, ModelRouteType routeType, String stage) {
        if (!properties.isEnabled()) {
            return callWithModel(fallbackChatModel, prompt);
        }
        List<ModelRoutingCandidate> candidates = modelSelector.select(routeType);
        if (candidates.isEmpty()) {
            return callWithModel(fallbackChatModel, prompt);
        }
        return modelRoutingExecutor.execute(candidates, candidate -> {
            ChatModel chatModel = resolveChatModel(candidate);
            // 异步发起模型调用
            CompletableFuture<String> firstPacketFuture = CompletableFuture.supplyAsync(() -> callWithModel(chatModel, prompt), ragRetrieveExecutor);
            // 阻塞等待，如果超时则抛出 TimeoutException，由外层 executor 捕获并记录失败
            String result = firstPacketAwaiter.awaitFirstPacket(firstPacketFuture);
            logger.info("首包探测通过: stage={}, candidate={}, state={}", stage, candidate.name(), modelHealthStore.stateOf(candidate.name()));
            return result;
        }, stage + "-first-packet");
    }

    /**
     * 流式调用模型，每产生一个 token 就通过 tokenConsumer 回调。
     * 方法本身阻塞到流完成，返回完整响应文本。
     */
    public String callStream(String prompt, ModelRouteType routeType, String stage,
                             Consumer<String> tokenConsumer) {
        if (!properties.isEnabled()) {
            return streamWithModel(fallbackChatModel, prompt, tokenConsumer);
        }
        List<ModelRoutingCandidate> candidates = modelSelector.select(routeType);
        if (candidates.isEmpty()) {
            return streamWithModel(fallbackChatModel, prompt, tokenConsumer);
        }
        return modelRoutingExecutor.execute(candidates, candidate -> {
            ChatModel chatModel = resolveChatModel(candidate);
            long start = System.currentTimeMillis();
            String response = streamWithModel(chatModel, prompt, tokenConsumer);
            long cost = System.currentTimeMillis() - start;
            logger.info("模型路由命中(流式): stage={}, candidate={}, provider={}, state={}, costMs={}",
                    stage, candidate.name(), candidate.provider(),
                    modelHealthStore.stateOf(candidate.name()), cost);
            return response;
        }, stage);
    }

    private String streamWithModel(ChatModel chatModel, String prompt, Consumer<String> tokenConsumer) {
        Flux<String> tokenFlux = ChatClient.builder(chatModel)
                .build()
                .prompt()
                .user(prompt)
                .stream()
                .content();

        List<String> tokens = tokenFlux
                .doOnNext(token -> {
                    if (token != null && !token.isEmpty()) {
                        tokenConsumer.accept(token);
                    }
                })
                .collectList()
                .block();

        if (tokens == null || tokens.isEmpty()) {
            throw new ModelRoutingException("模型流式返回为空");
        }
        return String.join("", tokens);
    }

    /**
     * 执行带有统一兜底逻辑的常规模型调用。
     * 当所有路由候选模型均失败时，不会抛出异常，而是调用 fallbackSupplier 提供降级结果。
     *
     * @param fallbackSupplier 降级结果提供者
     * @param prompt 输入提示词
     * @param routeType 路由类型
     * @param stage 业务阶段名称
     * @return 模型响应文本或降级结果
     */
    public String callSupplier(Supplier<String> fallbackSupplier, String prompt, ModelRouteType routeType, String stage) {
        try {
            return call(prompt, routeType, stage);
        } catch (RuntimeException ex) {
            routeFallbackCount.incrementAndGet();
            logger.warn("模型路由失败，执行降级: stage={}, reason={}", stage, ex.getMessage());
            return fallbackSupplier.get();
        }
    }

    /**
     * 执行带首包探测与兜底逻辑的模型调用。
     * 常用于前端对响应实时性要求极高的场景（如首题生成），超时或失败后返回统一的 fallback 文本。
     *
     * @param fallbackSupplier 降级结果提供者
     * @param prompt 输入提示词
     * @param routeType 路由类型
     * @param stage 业务阶段名称
     * @return 模型响应文本或降级结果
     */
    public String callWithFirstPacketProbeSupplier(Supplier<String> fallbackSupplier, String prompt, ModelRouteType routeType, String stage) {
        try {
            return callWithFirstPacketProbe(prompt, routeType, stage);
        } catch (RuntimeException ex) {
            if (containsTimeout(ex)) {
                firstPacketTimeoutCount.incrementAndGet();
            } else {
                firstPacketFailureCount.incrementAndGet();
            }
            routeFallbackCount.incrementAndGet();
            logger.warn("首包探测失败，执行统一兜底: stage={}, reason={}", stage, ex.getMessage());
            return fallbackSupplier.get();
        }
    }

    public Map<String, Object> snapshotStats() {
        return Map.of(
                "routeFallbackCount", routeFallbackCount.get(),
                "firstPacketTimeoutCount", firstPacketTimeoutCount.get(),
                "firstPacketFailureCount", firstPacketFailureCount.get(),
                "health", modelHealthStore.snapshotMetrics(),
                "states", modelHealthStore.snapshotStates(),
                "details", modelHealthStore.snapshotDetails()
        );
    }

    private ChatModel resolveChatModel(ModelRoutingCandidate candidate) {
        ChatModel chatModel = dynamicModelFactory.getByRoutingCandidate(candidate.provider(), candidate.beanName());
        if (chatModel == null) {
            throw new ModelRoutingException("找不到可用模型实例: " + candidate.name());
        }
        return chatModel;
    }

    public record RoutingResult(String content, int inputTokens, int outputTokens, long costMs) {}

    /**
     * 调用模型并返回包含 Token 消耗与耗时的完整元数据结果。
     * 常用于需要精确记录评估消耗或控制成本的场景。
     *
     * @param prompt 输入提示词
     * @param routeType 路由类型
     * @param stage 业务阶段名称
     * @return 包含响应内容与元数据的 RoutingResult
     */
    public RoutingResult callWithMetadata(String prompt, ModelRouteType routeType, String stage) {
        if (!properties.isEnabled()) {
            return callWithModelMetadata(fallbackChatModel, prompt);
        }
        List<ModelRoutingCandidate> candidates = modelSelector.select(routeType);
        if (candidates.isEmpty()) {
            return callWithModelMetadata(fallbackChatModel, prompt);
        }
        return modelRoutingExecutor.execute(candidates, candidate -> {
            ChatModel chatModel = resolveChatModel(candidate);
            long start = System.currentTimeMillis();
            RoutingResult result = callWithModelMetadata(chatModel, prompt);
            long cost = System.currentTimeMillis() - start;
            logger.info("模型路由命中: stage={}, candidate={}, provider={}, state={}, costMs={}, tokens={}+{}",
                    stage,
                    candidate.name(),
                    candidate.provider(),
                    modelHealthStore.stateOf(candidate.name()),
                    cost,
                    result.inputTokens(),
                    result.outputTokens());
            return result;
        }, stage);
    }

    private RoutingResult callWithModelMetadata(ChatModel chatModel, String prompt) {
        long start = System.currentTimeMillis();
        org.springframework.ai.chat.model.ChatResponse response = ChatClient.builder(chatModel)
                .build()
                .prompt()
                .user(prompt)
                .call()
                .chatResponse();
        
        long cost = System.currentTimeMillis() - start;
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new ModelRoutingException("模型返回为空");
        }
        
        String content = response.getResult().getOutput().getText();
        int inputTokens = 0;
        int outputTokens = 0;
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            inputTokens = response.getMetadata().getUsage().getPromptTokens() != null ? response.getMetadata().getUsage().getPromptTokens().intValue() : 0;
            outputTokens = response.getMetadata().getUsage().getCompletionTokens() != null ? response.getMetadata().getUsage().getCompletionTokens().intValue() : 0;
        }
        
        return new RoutingResult(content, inputTokens, outputTokens, cost);
    }

    private String callWithModel(ChatModel chatModel, String prompt) {
        return callWithModelMetadata(chatModel, prompt).content();
    }

    private boolean containsTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains("timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
