package com.example.interview.modelrouting;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

@Component
public class RoutingExecutionTemplate {

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
        if (!properties.isEnabled()) {
            return fallbackSupplier.get();
        }
        List<ModelRoutingCandidate> candidates = modelSelector.select(routeType);
        if (candidates.isEmpty()) {
            return fallbackSupplier.get();
        }
        return modelRoutingExecutor.execute(candidates, candidateExecutor::apply, stage);
    }
}
