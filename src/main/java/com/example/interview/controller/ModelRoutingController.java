package com.example.interview.controller;

import com.example.interview.modelrouting.ModelRoutingProperties;
import com.example.interview.modelrouting.RoutingChatService;
import com.example.interview.service.OllamaHealthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/model-routing")
public class ModelRoutingController {

    private final RoutingChatService routingChatService;
    private final ModelRoutingProperties properties;
    private final OllamaHealthService ollamaHealthService;

    public ModelRoutingController(RoutingChatService routingChatService,
                                  ModelRoutingProperties properties,
                                  OllamaHealthService ollamaHealthService) {
        this.routingChatService = routingChatService;
        this.properties = properties;
        this.ollamaHealthService = ollamaHealthService;
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", properties.isEnabled());
        data.put("defaultModel", properties.getDefaultModel());
        data.put("deepThinkingModel", properties.getDeepThinkingModel());
        data.put("candidateCount", properties.getCandidates().size());
        data.put("runtime", routingChatService.snapshotStats());
        data.put("ollama", ollamaHealthService.getHealthInfo());
        data.put("status", "healthy");
        return ResponseEntity.ok(data);
    }
}
