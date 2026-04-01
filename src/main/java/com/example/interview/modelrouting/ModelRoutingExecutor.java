package com.example.interview.modelrouting;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;

/**
 * 模型路由执行器。
 * 负责遍历候选模型列表，结合熔断器健康状态进行调用，处理失败重试与状态上报。
 */
@Component
public class ModelRoutingExecutor {

    private final ModelHealthStore modelHealthStore;

    public ModelRoutingExecutor(ModelHealthStore modelHealthStore) {
        this.modelHealthStore = modelHealthStore;
    }

    /**
     * 顺序执行候选模型调用，直到成功或全部耗尽。
     *
     * @param candidates 候选模型列表（通常已经过优先级与可用性排序）
     * @param invoker 针对单个候选模型的具体调用逻辑
     * @param stage 当前业务阶段名称（用于日志和异常提示）
     * @param <T> 返回结果类型
     * @return 首次成功的调用结果
     * @throws ModelRoutingException 当所有候选模型都不可用或调用失败时抛出
     */
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
