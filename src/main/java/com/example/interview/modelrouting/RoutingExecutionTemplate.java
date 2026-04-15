package com.example.interview.modelrouting;

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
        if (!properties.isEnabled()) {
            return fallbackSupplier.get();
        }
        List<ModelRoutingCandidate> candidates = modelSelector.select(routeType, preferredCandidateName);
        if (candidates.isEmpty()) {
            return fallbackSupplier.get();
        }
        try {
            return modelRoutingExecutor.execute(candidates, candidateExecutor::apply, stage);
        } catch (RuntimeException ex) {
            logger.warn("模型路由候选全部失败，执行fallback: stage={}, routeType={}, reason={}",
                    stage, routeType, ex.getMessage());
            return fallbackSupplier.get();
        }
    }
}
