package io.github.imzmq.interview.modelrouting.registry;

import io.github.imzmq.interview.entity.modelrouting.ModelCandidateDO;
import io.github.imzmq.interview.modelrouting.catalog.ModelCandidateService;
import io.github.imzmq.interview.modelrouting.core.ModelRoutingCandidate;
import io.github.imzmq.interview.modelrouting.core.ModelRoutingProperties;
import io.github.imzmq.interview.modelruntime.application.DynamicModelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DynamicModelPreheater {

    private static final Logger logger = LoggerFactory.getLogger(DynamicModelPreheater.class);

    private final ModelRoutingProperties modelRoutingProperties;
    private final ModelCandidateService modelCandidateService;
    private final DynamicModelFactory dynamicModelFactory;

    public DynamicModelPreheater(ModelRoutingProperties modelRoutingProperties,
                                 ModelCandidateService modelCandidateService,
                                 DynamicModelFactory dynamicModelFactory) {
        this.modelRoutingProperties = modelRoutingProperties;
        this.modelCandidateService = modelCandidateService;
        this.dynamicModelFactory = dynamicModelFactory;
    }

    @Async("profileUpdateExecutor")
    @EventListener(ApplicationReadyEvent.class)
    public void preheat() {
        if (!modelRoutingProperties.isEnabled()) {
            return;
        }
        List<ModelCandidateDO> candidates = modelCandidateService.listEnabled().stream()
                .filter(candidate -> Boolean.TRUE.equals(candidate.getIsPrimary()))
                .filter(candidate -> candidate.getBaseUrl() != null && !candidate.getBaseUrl().isBlank())
                .toList();

        for (ModelCandidateDO candidate : candidates) {
            try {
                dynamicModelFactory.getByCandidate(toRoutingCandidate(candidate));
                logger.info("动态 ChatModel 预热完成: name={}", candidate.getName());
            } catch (Exception ex) {
                logger.warn("动态 ChatModel 预热失败: name={}, error={}", candidate.getName(), ex.getMessage());
            }
        }
    }

    private ModelRoutingCandidate toRoutingCandidate(ModelCandidateDO candidate) {
        return ModelRoutingCandidate.from(candidate);
    }
}







