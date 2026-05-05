package io.github.imzmq.interview.controller;

import io.github.imzmq.interview.dto.modelrouting.ModelCandidateDTO;
import io.github.imzmq.interview.entity.modelrouting.ModelCandidateDO;
import io.github.imzmq.interview.modelrouting.probe.ModelProbeAwaiter;
import io.github.imzmq.interview.modelrouting.state.ModelHealthStore;
import io.github.imzmq.interview.modelrouting.api.ModelRoutingController;
import io.github.imzmq.interview.modelrouting.core.ModelRoutingProperties;
import io.github.imzmq.interview.modelrouting.core.RoutingChatService;
import io.github.imzmq.interview.modelrouting.application.DynamicModelFactory;
import io.github.imzmq.interview.modelrouting.application.OllamaHealthService;
import io.github.imzmq.interview.modelrouting.catalog.ModelCandidateService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelRoutingControllerTest {

    @Test
    void shouldReturnModelRoutingStats() {
        RoutingChatService routingChatService = mock(RoutingChatService.class);
        when(routingChatService.snapshotStats()).thenReturn(Map.of(
                "routeFallbackCount", 2L,
                "firstPacketTimeoutCount", 1L,
                "firstPacketFailureCount", 1L,
                "states", Map.of("m1", "OPEN")
        ));
        ModelRoutingProperties properties = new ModelRoutingProperties();
        OllamaHealthService ollamaHealthService = mock(OllamaHealthService.class);
        ModelCandidateService modelCandidateService = mock(ModelCandidateService.class);
        ModelHealthStore modelHealthStore = mock(ModelHealthStore.class);
        DynamicModelFactory dynamicModelFactory = mock(DynamicModelFactory.class);
        ModelProbeAwaiter modelProbeAwaiter = mock(ModelProbeAwaiter.class);
        when(ollamaHealthService.getHealthInfo()).thenReturn(Map.of("status", "UP", "serviceUp", true, "modelReady", true));
        properties.setEnabled(true);
        properties.setDefaultModel("deepseek-chat");
        properties.setDeepThinkingModel("deepseek-reasoner");
        ModelCandidateDO candidate = new ModelCandidateDO();
        candidate.setId(1L);
        candidate.setName("deepseek-chat");
        when(modelCandidateService.listAll()).thenReturn(List.of(candidate));

        ModelRoutingController controller = new ModelRoutingController(
                routingChatService,
                properties,
                ollamaHealthService,
                modelCandidateService,
                modelHealthStore,
                dynamicModelFactory,
                modelProbeAwaiter,
                false
        );
        ResponseEntity<Map<String, Object>> response = controller.stats();

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertEquals(true, body.get("enabled"));
        assertEquals("deepseek-chat", body.get("defaultModel"));
        assertEquals(1, body.get("candidateCount"));
        assertTrue(body.containsKey("runtime"));
        assertTrue(body.containsKey("ollama"));
    }

    @Test
    void shouldReturnMaskedCandidates() {
        RoutingChatService routingChatService = mock(RoutingChatService.class);
        ModelRoutingProperties properties = new ModelRoutingProperties();
        OllamaHealthService ollamaHealthService = mock(OllamaHealthService.class);
        ModelCandidateService modelCandidateService = mock(ModelCandidateService.class);
        ModelHealthStore modelHealthStore = mock(ModelHealthStore.class);
        DynamicModelFactory dynamicModelFactory = mock(DynamicModelFactory.class);
        ModelProbeAwaiter modelProbeAwaiter = mock(ModelProbeAwaiter.class);

        ModelCandidateDO entity = new ModelCandidateDO();
        entity.setId(1L);
        entity.setName("deepseek-chat");
        ModelCandidateDTO dto = new ModelCandidateDTO();
        dto.setId(1L);
        dto.setName("deepseek-chat");
        dto.setApiKeyMasked("sk-a****z");
        dto.setApiKeyConfigured(true);
        dto.setApiKeyReadable(true);
        when(modelCandidateService.listAll()).thenReturn(List.of(entity));
        when(modelCandidateService.toMaskedDto(entity)).thenReturn(dto);

        ModelRoutingController controller = new ModelRoutingController(
                routingChatService,
                properties,
                ollamaHealthService,
                modelCandidateService,
                modelHealthStore,
                dynamicModelFactory,
                modelProbeAwaiter,
                false
        );

        ResponseEntity<List<ModelCandidateDTO>> response = controller.listCandidates();

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        assertEquals("sk-a****z", response.getBody().getFirst().getApiKeyMasked());
        assertEquals(Boolean.FALSE, response.getBody().getFirst().getApiKeyCopyAllowed());
    }

    @Test
    void shouldRejectPlaintextKeyExportWhenDisabled() {
        RoutingChatService routingChatService = mock(RoutingChatService.class);
        ModelRoutingProperties properties = new ModelRoutingProperties();
        OllamaHealthService ollamaHealthService = mock(OllamaHealthService.class);
        ModelCandidateService modelCandidateService = mock(ModelCandidateService.class);
        ModelHealthStore modelHealthStore = mock(ModelHealthStore.class);
        DynamicModelFactory dynamicModelFactory = mock(DynamicModelFactory.class);
        ModelProbeAwaiter modelProbeAwaiter = mock(ModelProbeAwaiter.class);

        ModelRoutingController controller = new ModelRoutingController(
                routingChatService,
                properties,
                ollamaHealthService,
                modelCandidateService,
                modelHealthStore,
                dynamicModelFactory,
                modelProbeAwaiter,
                false
        );

        ResponseEntity<Map<String, String>> response = controller.copyKey(1L);

        assertEquals(403, response.getStatusCode().value());
        assertTrue(response.getBody().get("message").contains("默认关闭"));
    }

    @Test
    void shouldReturnPlaintextKeyWhenExportExplicitlyEnabled() {
        RoutingChatService routingChatService = mock(RoutingChatService.class);
        ModelRoutingProperties properties = new ModelRoutingProperties();
        OllamaHealthService ollamaHealthService = mock(OllamaHealthService.class);
        ModelCandidateService modelCandidateService = mock(ModelCandidateService.class);
        ModelHealthStore modelHealthStore = mock(ModelHealthStore.class);
        DynamicModelFactory dynamicModelFactory = mock(DynamicModelFactory.class);
        ModelProbeAwaiter modelProbeAwaiter = mock(ModelProbeAwaiter.class);
        when(modelCandidateService.decryptApiKey(1L)).thenReturn("sk-live");

        ModelRoutingController controller = new ModelRoutingController(
                routingChatService,
                properties,
                ollamaHealthService,
                modelCandidateService,
                modelHealthStore,
                dynamicModelFactory,
                modelProbeAwaiter,
                true
        );

        ResponseEntity<Map<String, String>> response = controller.copyKey(1L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("sk-live", response.getBody().get("apiKey"));
    }

    @Test
    void shouldExposeCopyPermissionOnCandidateDto() {
        RoutingChatService routingChatService = mock(RoutingChatService.class);
        ModelRoutingProperties properties = new ModelRoutingProperties();
        OllamaHealthService ollamaHealthService = mock(OllamaHealthService.class);
        ModelCandidateService modelCandidateService = mock(ModelCandidateService.class);
        ModelHealthStore modelHealthStore = mock(ModelHealthStore.class);
        DynamicModelFactory dynamicModelFactory = mock(DynamicModelFactory.class);
        ModelProbeAwaiter modelProbeAwaiter = mock(ModelProbeAwaiter.class);

        ModelCandidateDO entity = new ModelCandidateDO();
        entity.setId(1L);
        ModelCandidateDTO dto = new ModelCandidateDTO();
        dto.setId(1L);
        dto.setApiKeyConfigured(true);
        dto.setApiKeyReadable(true);
        when(modelCandidateService.listAll()).thenReturn(List.of(entity));
        when(modelCandidateService.toMaskedDto(entity)).thenReturn(dto);

        ModelRoutingController controller = new ModelRoutingController(
                routingChatService,
                properties,
                ollamaHealthService,
                modelCandidateService,
                modelHealthStore,
                dynamicModelFactory,
                modelProbeAwaiter,
                true
        );

        ResponseEntity<List<ModelCandidateDTO>> response = controller.listCandidates();

        assertEquals(Boolean.TRUE, response.getBody().getFirst().getApiKeyCopyAllowed());
    }
}











