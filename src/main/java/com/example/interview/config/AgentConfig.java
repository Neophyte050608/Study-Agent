package com.example.interview.config;

/**
 * 动态 Agent 配置实体。
 * 用于保存前端配置的单个 Agent 的大模型路由及启停策略。
 */
public class AgentConfig {
    
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
