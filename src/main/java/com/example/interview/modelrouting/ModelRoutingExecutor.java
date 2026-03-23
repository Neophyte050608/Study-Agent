package com.example.interview.modelrouting;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;

@Component
public class ModelRoutingExecutor {

    private final ModelHealthStore modelHealthStore;

    public ModelRoutingExecutor(ModelHealthStore modelHealthStore) {
        this.modelHealthStore = modelHealthStore;
    }

    public <T> T execute(List<ModelRoutingCandidate> candidates, Function<ModelRoutingCandidate, T> invoker, String stage) {
        if (candidates == null || candidates.isEmpty()) {
            throw new ModelRoutingException(stage + "无可用候选模型");
        }
        RuntimeException lastError = null;
        for (ModelRoutingCandidate candidate : candidates) {
            if (!modelHealthStore.canTry(candidate.name())) {
                continue;
            }
            if (modelHealthStore.stateOf(candidate.name()) == ModelCircuitState.HALF_OPEN) {
                modelHealthStore.markHalfOpenTrial(candidate.name());
            }
            try {
                T result = invoker.apply(candidate);
                modelHealthStore.markSuccess(candidate.name());
                return result;
            } catch (RuntimeException ex) {
                modelHealthStore.markFailure(candidate.name());
                lastError = ex;
            }
        }
        throw new ModelRoutingException(stage + "全部候选模型调用失败", lastError);
    }
}
