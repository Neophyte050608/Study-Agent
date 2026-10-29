package io.github.imzmq.interview.controller;

import io.github.imzmq.interview.modelrouting.api.ModelCandidateResponse;
import io.github.imzmq.interview.modelrouting.infrastructure.persistence.ModelCandidateDO;
import io.github.imzmq.interview.modelrouting.api.ModelRoutingController;
import io.github.imzmq.interview.modelrouting.core.ModelRoutingProperties;
import io.github.imzmq.interview.modelrouting.core.RoutingChatService;
import io.github.imzmq.interview.modelrouting.application.OllamaHealthService;
import io.github.imzmq.interview.modelrouting.application.ModelCandidateProbeService;
import io.github.imzmq.interview.modelrouting.catalog.ModelCandidateService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelRoutingControllerTest {

    @Test
    void ordinaryCandidateResponseNeverContainsWritableApiKey() {
        assertThat(ModelCandidateResponse.class.getDeclaredFields())
                .extracting(Field::getName)
                .doesNotContain("apiKey");
    }

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
        ModelCandidateProbeService modelCandidateProbeService = mock(ModelCandidateProbeService.class);
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
                modelCandidateProbeService,
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
        ModelCandidateProbeService modelCandidateProbeService = mock(ModelCandidateProbeService.class);

        ModelCandidateDO entity = new ModelCandidateDO();
        entity.setId(1L);
        entity.setName("deepseek-chat");
        ModelCandidateResponse dto = new ModelCandidateResponse();
        dto.setId(1L);
        dto.setName("deepseek-chat");
        dto.setApiKeyMasked("sk-a****z");
        dto.setApiKeyConfigured(true);
        dto.setApiKeyReadable(true);
        when(modelCandidateService.listAll()).thenReturn(List.of(entity));
        when(modelCandidateService.toResponse(entity)).thenReturn(dto);

        ModelRoutingController controller = new ModelRoutingController(
                routingChatService,
                properties,
                ollamaHealthService,
                modelCandidateService,
                modelCandidateProbeService,
                false
        );

        ResponseEntity<List<ModelCandidateResponse>> response = controller.listCandidates();

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
        ModelCandidateProbeService modelCandidateProbeService = mock(ModelCandidateProbeService.class);

        ModelRoutingController controller = new ModelRoutingController(
                routingChatService,
                properties,
                ollamaHealthService,
                modelCandidateService,
                modelCandidateProbeService,
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
        ModelCandidateProbeService modelCandidateProbeService = mock(ModelCandidateProbeService.class);
        when(modelCandidateService.decryptApiKey(1L)).thenReturn("sk-live");

        ModelRoutingController controller = new ModelRoutingController(
                routingChatService,
                properties,
                ollamaHealthService,
                modelCandidateService,
                modelCandidateProbeService,
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
        ModelCandidateProbeService modelCandidateProbeService = mock(ModelCandidateProbeService.class);

        ModelCandidateDO entity = new ModelCandidateDO();
        entity.setId(1L);
        ModelCandidateResponse dto = new ModelCandidateResponse();
        dto.setId(1L);
        dto.setApiKeyConfigured(true);
        dto.setApiKeyReadable(true);
        when(modelCandidateService.listAll()).thenReturn(List.of(entity));
        when(modelCandidateService.toResponse(entity)).thenReturn(dto);

        ModelRoutingController controller = new ModelRoutingController(
                routingChatService,
                properties,
                ollamaHealthService,
                modelCandidateService,
                modelCandidateProbeService,
                true
        );

        ResponseEntity<List<ModelCandidateResponse>> response = controller.listCandidates();

        assertEquals(Boolean.TRUE, response.getBody().getFirst().getApiKeyCopyAllowed());
    }
}
