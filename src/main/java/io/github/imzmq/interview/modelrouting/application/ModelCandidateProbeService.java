package io.github.imzmq.interview.modelrouting.application;

import io.github.imzmq.interview.modelrouting.catalog.ModelCandidateService;
import io.github.imzmq.interview.modelrouting.core.ModelRoutingCandidate;
import io.github.imzmq.interview.modelrouting.core.TimeoutHint;
import io.github.imzmq.interview.modelrouting.infrastructure.persistence.ModelCandidateDO;
import io.github.imzmq.interview.modelrouting.probe.ModelProbeAwaiter;
import io.github.imzmq.interview.modelrouting.state.ModelHealthStore;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Runs an active health probe for one configured model candidate.
 */
@Service
public class ModelCandidateProbeService {

    private final ModelCandidateService modelCandidateService;
    private final DynamicModelFactory dynamicModelFactory;
    private final ModelProbeAwaiter modelProbeAwaiter;
    private final ModelHealthStore modelHealthStore;

    public ModelCandidateProbeService(ModelCandidateService modelCandidateService,
                                      DynamicModelFactory dynamicModelFactory,
                                      ModelProbeAwaiter modelProbeAwaiter,
                                      ModelHealthStore modelHealthStore) {
        this.modelCandidateService = modelCandidateService;
        this.dynamicModelFactory = dynamicModelFactory;
        this.modelProbeAwaiter = modelProbeAwaiter;
        this.modelHealthStore = modelHealthStore;
    }

    public Optional<Map<String, Object>> probe(Long candidateId) {
        ModelCandidateDO entity = modelCandidateService.getById(candidateId);
        if (entity == null) {
            return Optional.empty();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("candidateName", entity.getName());
        ModelRoutingCandidate candidate = ModelRoutingCandidate.from(entity);
        ChatModel chatModel = dynamicModelFactory.getByCandidate(candidate);
        if (chatModel == null) {
            throw new IllegalStateException("无法创建模型实例");
        }
        long start = System.currentTimeMillis();
        Flux<String> tokenFlux = ChatClient.builder(chatModel)
                .build()
                .prompt()
                .user("ping")
                .stream()
                .content();
        String response = modelProbeAwaiter.awaitFirstToken(tokenFlux, TimeoutHint.FAST);
        long latency = System.currentTimeMillis() - start;
        modelHealthStore.markSuccess(entity.getName());
        result.put("status", "healthy");
        result.put("latencyMs", latency);
        result.put("response", response.length() > 100 ? response.substring(0, 100) + "..." : response);
        result.put("circuitState", modelHealthStore.stateOf(entity.getName()).name());
        return Optional.of(result);
    }
}
