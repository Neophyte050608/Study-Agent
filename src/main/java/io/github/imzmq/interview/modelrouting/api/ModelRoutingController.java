package io.github.imzmq.interview.modelrouting.api;

import io.github.imzmq.interview.dto.modelrouting.ModelCandidateDTO;
import io.github.imzmq.interview.entity.modelrouting.ModelCandidateDO;
import io.github.imzmq.interview.modelrouting.invoker.FirstTokenProbeInvoker;
import io.github.imzmq.interview.modelrouting.state.ModelHealthStore;
import io.github.imzmq.interview.modelrouting.core.ModelRoutingCandidate;
import io.github.imzmq.interview.modelrouting.core.ModelRoutingProperties;
import io.github.imzmq.interview.modelrouting.core.RoutingChatService;
import io.github.imzmq.interview.modelrouting.core.TimeoutHint;
import io.github.imzmq.interview.modelruntime.application.DynamicModelFactory;
import io.github.imzmq.interview.modelruntime.application.OllamaHealthService;
import io.github.imzmq.interview.modelrouting.catalog.ModelCandidateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/model-routing")
public class ModelRoutingController {

    private static final Logger logger = LoggerFactory.getLogger(ModelRoutingController.class);

    private final RoutingChatService routingChatService;
    private final ModelRoutingProperties properties;
    private final OllamaHealthService ollamaHealthService;
    private final ModelCandidateService modelCandidateService;
    private final ModelHealthStore modelHealthStore;
    private final DynamicModelFactory dynamicModelFactory;
    private final FirstTokenProbeInvoker firstTokenProbeInvoker;
    private final boolean allowPlaintextKeyExport;

    public ModelRoutingController(RoutingChatService routingChatService,
                                  ModelRoutingProperties properties,
                                  OllamaHealthService ollamaHealthService,
                                  ModelCandidateService modelCandidateService,
                                  ModelHealthStore modelHealthStore,
                                  DynamicModelFactory dynamicModelFactory,
                                  FirstTokenProbeInvoker firstTokenProbeInvoker,
                                  @Value("${app.security.allow-plaintext-key-export:false}") boolean allowPlaintextKeyExport) {
        this.routingChatService = routingChatService;
        this.properties = properties;
        this.ollamaHealthService = ollamaHealthService;
        this.modelCandidateService = modelCandidateService;
        this.modelHealthStore = modelHealthStore;
        this.dynamicModelFactory = dynamicModelFactory;
        this.firstTokenProbeInvoker = firstTokenProbeInvoker;
        this.allowPlaintextKeyExport = allowPlaintextKeyExport;
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", properties.isEnabled());
        data.put("defaultModel", properties.getDefaultModel());
        data.put("deepThinkingModel", properties.getDeepThinkingModel());
        data.put("retrievalModel", properties.getRetrievalModel());
        data.put("candidateCount", modelCandidateService.listAll().size());
        data.put("runtime", routingChatService.snapshotStats());
        data.put("ollama", ollamaHealthService.getHealthInfo());
        data.put("status", "healthy");
        return ResponseEntity.ok(data);
    }

    @GetMapping("/candidates")
    public ResponseEntity<List<ModelCandidateDTO>> listCandidates() {
        List<ModelCandidateDTO> candidates = modelCandidateService.listAll().stream()
                .map(modelCandidateService::toMaskedDto)
                .peek(this::applySecurityFlags)
                .toList();
        return ResponseEntity.ok(candidates);
    }

    @GetMapping("/candidates/{id}")
    public ResponseEntity<ModelCandidateDTO> getCandidate(@PathVariable Long id) {
        ModelCandidateDO entity = modelCandidateService.getById(id);
        if (entity == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(applySecurityFlags(modelCandidateService.toMaskedDto(entity)));
    }

    @PostMapping("/candidates")
    public ResponseEntity<ModelCandidateDTO> createCandidate(@RequestBody ModelCandidateDTO dto) {
        ModelCandidateDO created = modelCandidateService.create(dto);
        return ResponseEntity.ok(applySecurityFlags(modelCandidateService.toMaskedDto(created)));
    }

    @PutMapping("/candidates/{id}")
    public ResponseEntity<ModelCandidateDTO> updateCandidate(@PathVariable Long id, @RequestBody ModelCandidateDTO dto) {
        ModelCandidateDO updated = modelCandidateService.update(id, dto);
        return ResponseEntity.ok(applySecurityFlags(modelCandidateService.toMaskedDto(updated)));
    }

    @DeleteMapping("/candidates/{id}")
    public ResponseEntity<Map<String, String>> deleteCandidate(@PathVariable Long id) {
        modelCandidateService.delete(id);
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    @PostMapping("/candidates/{id}/toggle")
    public ResponseEntity<ModelCandidateDTO> toggleCandidate(@PathVariable Long id) {
        modelCandidateService.toggleEnabled(id);
        ModelCandidateDO entity = modelCandidateService.getById(id);
        if (entity == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(applySecurityFlags(modelCandidateService.toMaskedDto(entity)));
    }

    @PostMapping("/candidates/{id}/copy-key")
    public ResponseEntity<Map<String, String>> copyKey(@PathVariable Long id) {
        if (!allowPlaintextKeyExport) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "明文 API Key 导出默认关闭，请显式开启 app.security.allow-plaintext-key-export"));
        }
        String plainKey = modelCandidateService.decryptApiKey(id);
        return ResponseEntity.ok(Map.of("apiKey", plainKey == null ? "" : plainKey));
    }

    @PostMapping("/candidates/{id}/probe")
    public ResponseEntity<Map<String, Object>> probeCandidate(@PathVariable Long id) {
        ModelCandidateDO entity = modelCandidateService.getById(id);
        if (entity == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("candidateName", entity.getName());
        ModelRoutingCandidate candidate = ModelRoutingCandidate.from(entity);
        ChatModel chatModel = dynamicModelFactory.getByCandidate(candidate);
        if (chatModel == null) {
            throw new IllegalStateException("无法创建模型实例");
        }
        long start = System.currentTimeMillis();
        String response = firstTokenProbeInvoker.invoke(chatModel, null, "ping", TimeoutHint.FAST);
        long latency = System.currentTimeMillis() - start;
        modelHealthStore.markSuccess(entity.getName());
        result.put("status", "healthy");
        result.put("latencyMs", latency);
        result.put("response", response.length() > 100 ? response.substring(0, 100) + "..." : response);
        result.put("circuitState", modelHealthStore.stateOf(entity.getName()).name());
        return ResponseEntity.ok(result);
    }

    private ModelCandidateDTO applySecurityFlags(ModelCandidateDTO dto) {
        dto.setApiKeyCopyAllowed(allowPlaintextKeyExport);
        return dto;
    }
}












