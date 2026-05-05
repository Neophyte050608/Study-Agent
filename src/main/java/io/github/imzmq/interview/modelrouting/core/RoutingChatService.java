package io.github.imzmq.interview.modelrouting.core;

import io.github.imzmq.interview.modelruntime.application.DynamicModelFactory;
import io.github.imzmq.interview.knowledge.application.observability.TraceNodeDefinitions;
import io.github.imzmq.interview.knowledge.application.observability.TraceNodeHandle;
import io.github.imzmq.interview.knowledge.application.observability.TraceService;
import io.github.imzmq.interview.modelrouting.execution.ModelRoutingExecutor;
import io.github.imzmq.interview.modelrouting.execution.ModelSelector;
import io.github.imzmq.interview.modelrouting.execution.RoutingExecutionTemplate;
import io.github.imzmq.interview.modelrouting.invoker.FirstTokenProbeInvoker;
import io.github.imzmq.interview.modelrouting.invoker.MetadataChatInvoker;
import io.github.imzmq.interview.modelrouting.invoker.StreamingChatInvoker;
import io.github.imzmq.interview.modelrouting.probe.ModelProbeAwaiter;
import io.github.imzmq.interview.modelrouting.state.ModelHealthStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import io.github.imzmq.interview.core.trace.RAGTraceContext;
import io.github.imzmq.interview.common.api.BusinessException;
import io.github.imzmq.interview.common.api.ErrorCode;

@Service
public class RoutingChatService {

    private static final Logger logger = LoggerFactory.getLogger(RoutingChatService.class);

    private final ModelRoutingProperties properties;
    private final ModelHealthStore modelHealthStore;
    private final DynamicModelFactory dynamicModelFactory;
    private final RoutingExecutionTemplate routingExecutionTemplate;
    private final MetadataChatInvoker metadataChatInvoker;
    private final FirstTokenProbeInvoker firstTokenProbeInvoker;
    private final StreamingChatInvoker streamingChatInvoker;
    private final ChatModel fallbackChatModel;
    private final AtomicLong routeFallbackCount = new AtomicLong(0);
    private final AtomicLong firstPacketTimeoutCount = new AtomicLong(0);
    private final AtomicLong firstPacketFailureCount = new AtomicLong(0);

    @Autowired
    public RoutingChatService(
            @Qualifier("ragRetrieveExecutor") java.util.concurrent.Executor ragRetrieveExecutor,
            ModelRoutingProperties properties,
            ModelSelector modelSelector,
            ModelRoutingExecutor modelRoutingExecutor,
            ModelHealthStore modelHealthStore,
            DynamicModelFactory dynamicModelFactory,
            ModelProbeAwaiter modelProbeAwaiter,
            RoutingExecutionTemplate routingExecutionTemplate,
            MetadataChatInvoker metadataChatInvoker,
            FirstTokenProbeInvoker firstTokenProbeInvoker,
            StreamingChatInvoker streamingChatInvoker,
            @Qualifier("openAiChatModel") ChatModel fallbackChatModel
    ) {
        this.properties = properties;
        this.modelHealthStore = modelHealthStore;
        this.dynamicModelFactory = dynamicModelFactory;
        this.routingExecutionTemplate = routingExecutionTemplate;
        this.metadataChatInvoker = metadataChatInvoker;
        this.firstTokenProbeInvoker = firstTokenProbeInvoker;
        this.streamingChatInvoker = streamingChatInvoker;
        this.fallbackChatModel = fallbackChatModel;
    }

    public RoutingChatService(
            java.util.concurrent.Executor ragRetrieveExecutor,
            ModelRoutingProperties properties,
            ModelSelector modelSelector,
            ModelRoutingExecutor modelRoutingExecutor,
            ModelHealthStore modelHealthStore,
            DynamicModelFactory dynamicModelFactory,
            ModelProbeAwaiter modelProbeAwaiter,
            ChatModel fallbackChatModel
    ) {
        this(
                ragRetrieveExecutor,
                properties,
                modelSelector,
                modelRoutingExecutor,
                modelHealthStore,
                dynamicModelFactory,
                modelProbeAwaiter,
                new RoutingExecutionTemplate(properties, modelSelector, modelRoutingExecutor),
                new MetadataChatInvoker(),
                new FirstTokenProbeInvoker(modelProbeAwaiter),
                new StreamingChatInvoker(),
                fallbackChatModel
        );
    }

    public String call(String prompt, ModelRouteType routeType, String stage) {
        return routingExecutionTemplate.execute(routeType, null, stage,
                () -> callWithModel(fallbackChatModel, prompt),
                candidate -> {
            ChatModel chatModel = resolveChatModel(candidate);
            RoutingResult result = callWithModelMetadata(chatModel, null, prompt);
            logger.info("模型路由命中: stage={}, candidate={}, provider={}, state={}, costMs={}",
                    stage,
                    candidate.name(),
                    candidate.provider(),
                    modelHealthStore.stateOf(candidate.name()),
                    result.costMs());
            return result.content();
        });
    }

    public String callWithoutFallback(String prompt, ModelRouteType routeType, String stage) {
        return callWithoutFallback(prompt, routeType, null, stage);
    }

    public String callWithoutFallback(String prompt, ModelRouteType routeType, String preferredCandidateName, String stage) {
        return routingExecutionTemplate.execute(routeType, preferredCandidateName, stage,
                () -> {
                    throw new BusinessException(ErrorCode.MODEL_NO_CANDIDATE, "routeType=" + routeType + ", stage=" + stage);
                },
                candidate -> {
                    ChatModel chatModel = resolveChatModel(candidate);
                    RoutingResult result = callWithModelMetadata(chatModel, null, prompt);
                    logger.info("模型路由命中(无兜底): stage={}, candidate={}, provider={}, state={}, costMs={}",
                            stage,
                            candidate.name(),
                            candidate.provider(),
                            modelHealthStore.stateOf(candidate.name()),
                            result.costMs());
                    return result.content();
                });
    }

    public String call(String systemPrompt, String userPrompt, ModelRouteType routeType, String stage) {
        return call(systemPrompt, userPrompt, routeType, null, stage);
    }

    public String call(String systemPrompt, String userPrompt, ModelRouteType routeType, String preferredCandidateName, String stage) {
        return routingExecutionTemplate.execute(routeType, preferredCandidateName, stage,
                () -> callWithModelMetadata(fallbackChatModel, systemPrompt, userPrompt).content(),
                candidate -> {
            ChatModel chatModel = resolveChatModel(candidate);
            RoutingResult result = callWithModelMetadata(chatModel, systemPrompt, userPrompt);
            logger.info("模型路由命中: stage={}, candidate={}, provider={}, state={}, costMs={}",
                    stage,
                    candidate.name(),
                    candidate.provider(),
                    modelHealthStore.stateOf(candidate.name()),
                    result.costMs());
            return result.content();
        });
    }

    /**
     * 带首包探测的模型调用。
     * 使用 CompletableFuture 异步发起请求，并结合 ModelProbeAwaiter 检查响应时间。
     * 如果某候选模型响应过慢（未在规定时间内返回首包），将抛出超时异常，触发熔断器状态转换并降级到下一个模型。
     */
    public String callWithFirstPacketProbe(String prompt, ModelRouteType routeType, TimeoutHint hint, String stage) {
        return routingExecutionTemplate.execute(routeType, stage + "-first-token",
                () -> callWithModel(fallbackChatModel, prompt),
                candidate -> {
            ChatModel chatModel = resolveChatModel(candidate);
            String result = firstTokenProbeInvoker.invoke(chatModel, null, prompt, hint);
            logger.info("首Token探测通过: stage={}, candidate={}, hint={}, state={}",
                    stage, candidate.name(), hint, modelHealthStore.stateOf(candidate.name()));
            return result;
        });
    }

    public String callWithFirstPacketProbe(String prompt, ModelRouteType routeType, String stage) {
        return callWithFirstPacketProbe(prompt, routeType, TimeoutHint.NORMAL, stage);
    }

    /**
     * 流式调用模型，每产生一个 token 就通过 tokenConsumer 回调。
     * 方法本身阻塞到流完成，返回完整响应文本。
     */
    public String callStream(String prompt, ModelRouteType routeType, String stage,
                             Consumer<String> tokenConsumer) {
        return routingExecutionTemplate.execute(routeType, stage,
                () -> streamWithModel(fallbackChatModel, null, prompt, tokenConsumer),
                candidate -> {
            ChatModel chatModel = resolveChatModel(candidate);
            long start = System.currentTimeMillis();
            String response = streamWithModel(chatModel, null, prompt, tokenConsumer);
            long cost = System.currentTimeMillis() - start;
            logger.info("模型路由命中(流式): stage={}, candidate={}, provider={}, state={}, costMs={}",
                    stage, candidate.name(), candidate.provider(),
                    modelHealthStore.stateOf(candidate.name()), cost);
            return response;
        });
    }

    public String callStream(String systemPrompt, String userPrompt, ModelRouteType routeType, String stage,
                             Consumer<String> tokenConsumer) {
        return routingExecutionTemplate.execute(routeType, stage,
                () -> streamWithModel(fallbackChatModel, systemPrompt, userPrompt, tokenConsumer),
                candidate -> {
            ChatModel chatModel = resolveChatModel(candidate);
            long start = System.currentTimeMillis();
            String response = streamWithModel(chatModel, systemPrompt, userPrompt, tokenConsumer);
            long cost = System.currentTimeMillis() - start;
            logger.info("模型路由命中(流式): stage={}, candidate={}, provider={}, state={}, costMs={}",
                    stage, candidate.name(), candidate.provider(),
                    modelHealthStore.stateOf(candidate.name()), cost);
            return response;
        });
    }

    public String callStreamWithTrace(String systemPrompt,
                                      String userPrompt,
                                      ModelRouteType routeType,
                                      String stage,
                                      Consumer<String> tokenConsumer,
                                      TraceService traceService) {
        String traceId = RAGTraceContext.getTraceId();
        String parentNodeId = RAGTraceContext.getCurrentNodeId();
        TraceNodeHandle streamHandle = traceService.startChild(
                traceId,
                parentNodeId,
                TraceNodeDefinitions.LLM_STREAM,
                Map.of("status", "RUNNING")
        );
        AtomicBoolean firstTokenRecorded = new AtomicBoolean(false);
        long start = System.currentTimeMillis();
        try {
            String result = callStream(systemPrompt, userPrompt, routeType, stage, token -> {
                if (firstTokenRecorded.compareAndSet(false, true)) {
                    long firstTokenMs = System.currentTimeMillis() - start;
                    TraceNodeHandle firstTokenHandle = traceService.startChild(
                            traceId,
                            streamHandle.nodeId(),
                            TraceNodeDefinitions.LLM_FIRST_TOKEN,
                            Map.of("firstTokenMs", firstTokenMs, "status", "COMPLETED")
                    );
                    traceService.success(firstTokenHandle, Map.of("firstTokenMs", firstTokenMs, "status", "COMPLETED"));
                }
                tokenConsumer.accept(token);
            });
            long completionMs = System.currentTimeMillis() - start;
            traceService.success(streamHandle, Map.of("status", "COMPLETED", "completionMs", completionMs));
            return result;
        } catch (RuntimeException ex) {
            long completionMs = System.currentTimeMillis() - start;
            traceService.fail(streamHandle, ex.getMessage(), Map.of("status", "FAILED", "completionMs", completionMs));
            throw ex;
        }
    }

    private String streamWithModel(ChatModel chatModel, String systemPrompt, String userPrompt, Consumer<String> tokenConsumer) {
        return streamingChatInvoker.invoke(chatModel, systemPrompt, userPrompt, tokenConsumer);
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
    public String callWithFirstPacketProbeSupplier(Supplier<String> fallbackSupplier,
                                                   String prompt, ModelRouteType routeType,
                                                   TimeoutHint hint, String stage) {
        try {
            return callWithFirstPacketProbe(prompt, routeType, hint, stage);
        } catch (RuntimeException ex) {
            if (containsTimeout(ex)) {
                firstPacketTimeoutCount.incrementAndGet();
            } else {
                firstPacketFailureCount.incrementAndGet();
            }
            routeFallbackCount.incrementAndGet();
            logger.warn("首Token探测失败，执行统一兜底: stage={}, hint={}, reason={}", stage, hint, ex.getMessage());
            return fallbackSupplier.get();
        }
    }

    public String callWithFirstPacketProbeSupplier(Supplier<String> fallbackSupplier,
                                                   String systemPrompt,
                                                   String userPrompt,
                                                   ModelRouteType routeType,
                                                   String stage) {
        return callWithFirstPacketProbeSupplier(fallbackSupplier, systemPrompt, userPrompt, routeType, TimeoutHint.NORMAL, stage);
    }

    public String callWithFirstPacketProbeSupplier(Supplier<String> fallbackSupplier,
                                                   String systemPrompt,
                                                   String userPrompt,
                                                   ModelRouteType routeType,
                                                   TimeoutHint hint,
                                                   String stage) {
        try {
            return routingExecutionTemplate.execute(routeType, stage + "-first-token",
                    () -> callWithModelMetadata(fallbackChatModel, systemPrompt, userPrompt).content(),
                    candidate -> {
                ChatModel chatModel = resolveChatModel(candidate);
                String result = firstTokenProbeInvoker.invoke(chatModel, systemPrompt, userPrompt, hint);
                logger.info("首Token探测通过: stage={}, candidate={}, hint={}, state={}",
                        stage, candidate.name(), hint, modelHealthStore.stateOf(candidate.name()));
                return result;
            });
        } catch (RuntimeException ex) {
            if (containsTimeout(ex)) {
                firstPacketTimeoutCount.incrementAndGet();
            } else {
                firstPacketFailureCount.incrementAndGet();
            }
            routeFallbackCount.incrementAndGet();
            logger.warn("首Token探测失败，执行统一兜底: stage={}, hint={}, reason={}", stage, hint, ex.getMessage());
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
        ChatModel chatModel = dynamicModelFactory.getByCandidate(candidate);
        if (chatModel == null) {
            throw new BusinessException(ErrorCode.MODEL_INSTANCE_NOT_FOUND, candidate.name());
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
        return routingExecutionTemplate.execute(routeType, stage,
                () -> callWithModelMetadata(fallbackChatModel, null, prompt),
                candidate -> {
            ChatModel chatModel = resolveChatModel(candidate);
            long start = System.currentTimeMillis();
            RoutingResult result = callWithModelMetadata(chatModel, null, prompt);
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
        });
    }

    public RoutingResult callWithMetadata(String systemPrompt, String userPrompt, ModelRouteType routeType, String stage) {
        return routingExecutionTemplate.execute(routeType, stage,
                () -> callWithModelMetadata(fallbackChatModel, systemPrompt, userPrompt),
                candidate -> {
            ChatModel chatModel = resolveChatModel(candidate);
            long start = System.currentTimeMillis();
            RoutingResult result = callWithModelMetadata(chatModel, systemPrompt, userPrompt);
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
        });
    }

    private RoutingResult callWithModelMetadata(ChatModel chatModel, String systemPrompt, String userPrompt) {
        return metadataChatInvoker.invoke(chatModel, systemPrompt, userPrompt);
    }

    private String callWithModel(ChatModel chatModel, String prompt) {
        return callWithModelMetadata(chatModel, null, prompt).content();
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





