package com.example.interview.service;

import com.example.interview.config.AgentConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 动态配置服务。
 * 
 * 职责：
 * 1. 管理 agent_configs.json 的持久化。
 * 2. 提供运行时配置查询。
 */
@Service
public class AgentConfigService {

    private static final Logger logger = LoggerFactory.getLogger(AgentConfigService.class);
    private static final String CONFIG_FILE = "agent_configs.json";
    private final ObjectMapper objectMapper;

    // 内存缓存
    private final Map<String, AgentConfig> configMap = new ConcurrentHashMap<>();

    public AgentConfigService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        loadConfig();
    }

    private void loadConfig() {
        File file = new File(CONFIG_FILE);
        if (file.exists()) {
            try {
                Map<String, AgentConfig> loaded = objectMapper.readValue(file, new TypeReference<Map<String, AgentConfig>>() {});
                if (loaded != null) {
                    configMap.putAll(loaded);
                }
            } catch (IOException e) {
                logger.error("Failed to load agent configs from {}", CONFIG_FILE, e);
            }
        } else {
            // 初始化默认配置
            configMap.put("EvaluationAgent", new AgentConfig(true, "OPENAI", "gpt-4o", "", 0.7));
            configMap.put("DecisionLayerAgent", new AgentConfig(true, "ZHIPUAI", "glm-4", "", 0.3));
            configMap.put("LearningProfileAgent", new AgentConfig(true, "OLLAMA", "llama3", "", 0.9));
            saveConfig();
        }
    }

    private synchronized void saveConfig() {
        try {
            objectMapper.writeValue(new File(CONFIG_FILE), configMap);
        } catch (IOException e) {
            logger.error("Failed to save agent configs to {}", CONFIG_FILE, e);
        }
    }

    /**
     * 获取指定 Agent 的配置，如果不存在则返回默认开启的配置。
     */
    public AgentConfig getConfig(String agentName) {
        return configMap.getOrDefault(agentName, new AgentConfig(true, "OPENAI", "gpt-4o", "", 0.7));
    }

    /**
     * 更新单个 Agent 的配置并持久化。
     */
    public void updateConfig(String agentName, AgentConfig newConfig) {
        configMap.put(agentName, newConfig);
        saveConfig();
    }

    /**
     * 批量更新配置
     */
    public void updateAllConfigs(Map<String, AgentConfig> newConfigs) {
        configMap.putAll(newConfigs);
        saveConfig();
    }

    /**
     * 获取所有配置
     */
    public Map<String, AgentConfig> getAllConfigs() {
        return configMap;
    }
}
