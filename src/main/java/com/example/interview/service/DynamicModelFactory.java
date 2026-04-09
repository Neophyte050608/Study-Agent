package com.example.interview.service;

import com.example.interview.config.AgentConfig;
import com.example.interview.service.model.ModelBeanResolver;
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
    private final ModelBeanResolver modelBeanResolver;

    public DynamicModelFactory(AgentConfigService agentConfigService,
                               ApplicationContext applicationContext,
                               ModelBeanResolver modelBeanResolver) {
        this.agentConfigService = agentConfigService;
        this.applicationContext = applicationContext;
        this.modelBeanResolver = modelBeanResolver;
    }

    /**
     * 根据 Agent 名称获取其专属的模型客户端。
     * 如果该 Agent 被关闭，或者指定提供商不合法，将回退到默认的主模型。
     */
    public ChatModel getForAgent(String agentName) {
        AgentConfig config = agentConfigService.getConfig(agentName);
        return getByBeanNameOrDefault(modelBeanResolver.resolveForAgent(config));
    }

    public ChatModel getByRoutingCandidate(String provider, String beanName) {
        return getByBeanNameOrDefault(modelBeanResolver.resolveByRoutingCandidate(provider, beanName));
    }

    private ChatModel getByBeanNameOrDefault(String beanName) {
        try {
            return applicationContext.getBean(beanName, ChatModel.class);
        } catch (Exception ignored) {
        }
        try {
            return applicationContext.getBean(modelBeanResolver.defaultBeanName(), ChatModel.class);
        } catch (Exception ignored) {
            return null;
        }
    }
}
