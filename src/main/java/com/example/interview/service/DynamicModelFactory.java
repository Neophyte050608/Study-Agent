package com.example.interview.service;

import com.example.interview.config.AgentConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

/**
 * 动态模型工厂。
 * 
 * 职责：
 * 根据 Agent 的动态配置，返回对应的 ChatModel 实例。
 */
@Service
public class DynamicModelFactory {

    private final AgentConfigService agentConfigService;
    private final ApplicationContext applicationContext;

    public DynamicModelFactory(AgentConfigService agentConfigService, ApplicationContext applicationContext) {
        this.agentConfigService = agentConfigService;
        this.applicationContext = applicationContext;
    }

    /**
     * 根据 Agent 名称获取其专属的模型客户端。
     * 如果该 Agent 被关闭，或者指定提供商不合法，将回退到默认的主模型。
     */
    public ChatModel getForAgent(String agentName) {
        AgentConfig config = agentConfigService.getConfig(agentName);

        try {
            if ("ZHIPUAI".equalsIgnoreCase(config.getProvider()) || "ZHIPU".equalsIgnoreCase(config.getProvider())) {
                return applicationContext.getBean("zhiPuAiChatModel", ChatModel.class);
            } else if ("OLLAMA".equalsIgnoreCase(config.getProvider())) {
                return applicationContext.getBean("ollamaChatModel", ChatModel.class);
            }
        } catch (Exception e) {
            // 如果 Bean 不存在，退回到主模型
        }
        
        // 默认返回 OpenAI 或主模型
        return applicationContext.getBean("openAiChatModel", ChatModel.class);
    }

    public ChatModel getByRoutingCandidate(String provider, String beanName) {
        if (beanName != null && !beanName.isBlank()) {
            try {
                return applicationContext.getBean(beanName, ChatModel.class);
            } catch (Exception ignored) {
            }
        }
        if ("ZHIPUAI".equalsIgnoreCase(provider) || "ZHIPU".equalsIgnoreCase(provider)) {
            try {
                return applicationContext.getBean("zhiPuAiChatModel", ChatModel.class);
            } catch (Exception ignored) {
            }
        }
        if ("OLLAMA".equalsIgnoreCase(provider)) {
            try {
                return applicationContext.getBean("ollamaChatModel", ChatModel.class);
            } catch (Exception ignored) {
            }
        }
        try {
            return applicationContext.getBean("openAiChatModel", ChatModel.class);
        } catch (Exception ignored) {
            return null;
        }
    }
}
