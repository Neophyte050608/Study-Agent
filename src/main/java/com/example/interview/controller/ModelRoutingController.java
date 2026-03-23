package com.example.interview.controller;

import com.example.interview.modelrouting.ModelRoutingProperties;
import com.example.interview.modelrouting.RoutingChatService;
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

    public ModelRoutingController(RoutingChatService routingChatService, ModelRoutingProperties properties) {
        this.routingChatService = routingChatService;
        this.properties = properties;
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", properties.isEnabled());
        data.put("defaultModel", properties.getDefaultModel());
        data.put("deepThinkingModel", properties.getDeepThinkingModel());
        data.put("candidateCount", properties.getCandidates().size());
        data.put("runtime", routingChatService.snapshotStats());
        data.put("status", "healthy");
        return ResponseEntity.ok(data);
    }
}
