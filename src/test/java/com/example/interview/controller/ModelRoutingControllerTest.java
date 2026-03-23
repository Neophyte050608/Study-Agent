package com.example.interview.controller;

import com.example.interview.modelrouting.ModelRoutingProperties;
import com.example.interview.modelrouting.RoutingChatService;
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
        properties.setEnabled(true);
        properties.setDefaultModel("deepseek-chat");
        properties.setDeepThinkingModel("deepseek-reasoner");
        ModelRoutingProperties.Candidate candidate = new ModelRoutingProperties.Candidate();
        candidate.setName("deepseek-chat");
        properties.setCandidates(List.of(candidate));

        ModelRoutingController controller = new ModelRoutingController(routingChatService, properties);
        ResponseEntity<Map<String, Object>> response = controller.stats();

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertEquals(true, body.get("enabled"));
        assertEquals("deepseek-chat", body.get("defaultModel"));
        assertEquals(1, body.get("candidateCount"));
        assertTrue(body.containsKey("runtime"));
    }
}
