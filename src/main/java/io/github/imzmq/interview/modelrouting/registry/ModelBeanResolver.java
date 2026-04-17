package io.github.imzmq.interview.modelrouting.registry;

import io.github.imzmq.interview.config.agent.AgentConfig;
import org.springframework.stereotype.Component;

@Component
public class ModelBeanResolver {

    private static final String DEFAULT_BEAN_NAME = "openAiChatModel";

    public String resolveForAgent(AgentConfig config) {
        if (config == null) {
            return DEFAULT_BEAN_NAME;
        }
        return resolveByProvider(config.getProvider(), null);
    }

    public String resolveByRoutingCandidate(String provider, String beanName) {
        if (beanName != null && !beanName.isBlank()) {
            return beanName.trim();
        }
        return resolveByProvider(provider, DEFAULT_BEAN_NAME);
    }

    public String defaultBeanName() {
        return DEFAULT_BEAN_NAME;
    }

    private String resolveByProvider(String provider, String fallbackBeanName) {
        if ("ZHIPUAI".equalsIgnoreCase(provider) || "ZHIPU".equalsIgnoreCase(provider)) {
            return "zhiPuAiChatModel";
        }
        if ("OLLAMA".equalsIgnoreCase(provider)) {
            return "ollamaChatModel";
        }
        return fallbackBeanName == null || fallbackBeanName.isBlank() ? DEFAULT_BEAN_NAME : fallbackBeanName;
    }
}




