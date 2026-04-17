package io.github.imzmq.interview.agent.api;

import io.github.imzmq.interview.config.agent.AgentConfig;
import io.github.imzmq.interview.agent.application.AgentConfigService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 动态 Agent 配置 API。
 */
@RestController
@RequestMapping("/api/settings/agents")
public class AgentConfigController {

    private final AgentConfigService agentConfigService;

    public AgentConfigController(AgentConfigService agentConfigService) {
        this.agentConfigService = agentConfigService;
    }

    @GetMapping
    public Map<String, AgentConfig> getAllConfigs() {
        return agentConfigService.getAllConfigs();
    }

    @PostMapping
    public Map<String, String> updateConfigs(@RequestBody Map<String, AgentConfig> configs) {
        agentConfigService.updateAllConfigs(configs);
        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        return response;
    }
}






