package io.github.imzmq.interview.modelrouting.probe;

import io.github.imzmq.interview.entity.modelrouting.ModelCandidateDO;
import io.github.imzmq.interview.modelruntime.application.DynamicModelFactory;
import io.github.imzmq.interview.modelrouting.catalog.ModelCandidateService;
import io.github.imzmq.interview.modelrouting.state.ModelHealthStore;
import io.github.imzmq.interview.modelrouting.core.ModelRoutingCandidate;
import io.github.imzmq.interview.modelrouting.core.ModelRoutingProperties;
import io.github.imzmq.interview.modelrouting.core.TimeoutHint;
import io.github.imzmq.interview.modelrouting.invoker.FirstTokenProbeInvoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ModelHealthProbeScheduler {

    private static final Logger logger = LoggerFactory.getLogger(ModelHealthProbeScheduler.class);

    private final ModelCandidateService modelCandidateService;
    private final ModelRoutingProperties properties;
    private final DynamicModelFactory dynamicModelFactory;
    private final FirstTokenProbeInvoker firstTokenProbeInvoker;
    private final ModelHealthStore modelHealthStore;

    public ModelHealthProbeScheduler(ModelCandidateService modelCandidateService,
                                     ModelRoutingProperties properties,
                                     DynamicModelFactory dynamicModelFactory,
                                     FirstTokenProbeInvoker firstTokenProbeInvoker,
                                     ModelHealthStore modelHealthStore) {
        this.modelCandidateService = modelCandidateService;
        this.properties = properties;
        this.dynamicModelFactory = dynamicModelFactory;
        this.firstTokenProbeInvoker = firstTokenProbeInvoker;
        this.modelHealthStore = modelHealthStore;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    public void probePrimaryModels() {
        if (!properties.isEnabled()) {
            return;
        }
        List<ModelCandidateDO> primaries = modelCandidateService.listEnabled().stream()
                .filter(candidate -> Boolean.TRUE.equals(candidate.getIsPrimary()))
                .toList();

        for (ModelCandidateDO primary : primaries) {
            try {
                ModelRoutingCandidate candidate = new ModelRoutingCandidate(
                        primary.getName(),
                        primary.getProvider(),
                        primary.getModel(),
                        "",
                        primary.getPriority() == null ? 100 : primary.getPriority(),
                        Boolean.TRUE.equals(primary.getSupportsThinking()),
                        primary.getBaseUrl() == null ? "" : primary.getBaseUrl(),
                        primary.getApiKeyEncrypted() == null ? "" : primary.getApiKeyEncrypted(),
                        primary.getRouteType() == null ? "" : primary.getRouteType(),
                        "DATABASE"
                );
                ChatModel chatModel = dynamicModelFactory.getByCandidate(candidate);
                if (chatModel == null) {
                    modelHealthStore.markFailure(primary.getName(), "无法创建模型实例");
                    continue;
                }
                firstTokenProbeInvoker.invoke(chatModel, null, "ping", TimeoutHint.FAST);
                modelHealthStore.markSuccess(primary.getName());
                logger.debug("主模型健康探测通过: {}", primary.getName());
            } catch (Exception ex) {
                modelHealthStore.markFailure(primary.getName(), ex.getMessage());
                logger.warn("主模型健康探测失败: name={}, error={}", primary.getName(), ex.getMessage());
            }
        }
    }
}








