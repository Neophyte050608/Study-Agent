package io.github.imzmq.interview.modelrouting.execution;

import io.github.imzmq.interview.modelrouting.core.ModelRouteType;
import io.github.imzmq.interview.modelrouting.core.ModelRoutingCandidate;
import io.github.imzmq.interview.modelrouting.core.ModelRoutingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

@Component
public class RoutingExecutionTemplate {

    private static final Logger logger = LoggerFactory.getLogger(RoutingExecutionTemplate.class);

    private final ModelRoutingProperties properties;
    private final ModelSelector modelSelector;
    private final ModelRoutingExecutor modelRoutingExecutor;

    public RoutingExecutionTemplate(ModelRoutingProperties properties,
                                    ModelSelector modelSelector,
                                    ModelRoutingExecutor modelRoutingExecutor) {
        this.properties = properties;
        this.modelSelector = modelSelector;
        this.modelRoutingExecutor = modelRoutingExecutor;
    }

    public <T> T execute(ModelRouteType routeType,
                         String stage,
                         Supplier<T> fallbackSupplier,
                         Function<ModelRoutingCandidate, T> candidateExecutor) {
        return execute(routeType, null, stage, fallbackSupplier, candidateExecutor);
    }

    public <T> T execute(ModelRouteType routeType,
                         String preferredCandidateName,
                         String stage,
                         Supplier<T> fallbackSupplier,
                         Function<ModelRoutingCandidate, T> candidateExecutor) {
        // 若全局关闭模型路由，则直接走业务 fallback（通常是默认模型调用链）。
        if (!properties.isEnabled()) {
            return fallbackSupplier.get();
        }
        // 选择当前路由类型下的候选模型（含 preferred 优先策略）。
        List<ModelRoutingCandidate> candidates = modelSelector.select(routeType, preferredCandidateName);
        // 没有候选时也回退，保证业务可用性。
        if (candidates.isEmpty()) {
            return fallbackSupplier.get();
        }
        try {
            // 执行候选模型调用（内部包含健康状态与失败切换）。
            return modelRoutingExecutor.execute(candidates, candidateExecutor::apply, stage);
        } catch (RuntimeException ex) {
            logger.warn("模型路由候选全部失败，执行fallback: stage={}, routeType={}, reason={}",
                    stage, routeType, ex.getMessage());
            // 候选全失败，兜底回退。
            return fallbackSupplier.get();
        }
    }
}



