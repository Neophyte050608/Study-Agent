package io.github.imzmq.interview.config.agent;

import java.io.Serializable;

/**
 * 代理（Agent）配置实体。
 * 用于定义模型供应商、模型名称、参数等。
 * 实现 Serializable 以支持 Redis 缓存序列化。
 */
public class AgentConfig implements Serializable {
    
    private static final long serialVersionUID = 1L;

    private boolean enabled = true;
    private String provider; // OPENAI, ZHIPU, OLLAMA
    private String modelName;
    private String apiKey;
    private Double temperature;

    public AgentConfig() {}

    public AgentConfig(boolean enabled, String provider, String modelName, String apiKey, Double temperature) {
        this.enabled = enabled;
        this.provider = provider;
        this.modelName = modelName;
        this.apiKey = apiKey;
        this.temperature = temperature;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }
}

