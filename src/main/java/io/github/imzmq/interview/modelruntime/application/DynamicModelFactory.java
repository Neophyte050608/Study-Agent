package io.github.imzmq.interview.modelruntime.application;

import io.github.imzmq.interview.modelrouting.core.ModelRoutingCandidate;
import io.github.imzmq.interview.modelrouting.security.ApiKeyEncryptor;
import io.github.imzmq.interview.modelrouting.registry.DynamicChatModelRegistry;
import io.github.imzmq.interview.modelrouting.registry.ModelBeanResolver;
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

    private final ApplicationContext applicationContext;
    private final ModelBeanResolver modelBeanResolver;
    private final DynamicChatModelRegistry dynamicChatModelRegistry;
    private final ApiKeyEncryptor apiKeyEncryptor;

    public DynamicModelFactory(ApplicationContext applicationContext,
                               ModelBeanResolver modelBeanResolver,
                               DynamicChatModelRegistry dynamicChatModelRegistry,
                               ApiKeyEncryptor apiKeyEncryptor) {
        this.applicationContext = applicationContext;
        this.modelBeanResolver = modelBeanResolver;
        this.dynamicChatModelRegistry = dynamicChatModelRegistry;
        this.apiKeyEncryptor = apiKeyEncryptor;
    }

    public ChatModel getByCandidate(ModelRoutingCandidate candidate) {
        if (candidate.baseUrl() != null && !candidate.baseUrl().isBlank()) {
            ChatModel cachedModel = dynamicChatModelRegistry.getIfPresent(candidate.name());
            if (cachedModel != null) {
                return cachedModel;
            }
            String apiKey = "";
            if (candidate.apiKeyRef() != null && !candidate.apiKeyRef().isBlank()) {
                apiKey = apiKeyEncryptor.decrypt(candidate.apiKeyRef());
            }
            return dynamicChatModelRegistry.getOrCreate(
                    candidate.name(),
                    candidate.baseUrl(),
                    apiKey,
                    candidate.model()
            );
        }
        return getByBeanNameOrDefault(modelBeanResolver.resolveByRoutingCandidate(candidate.provider(), candidate.beanName()));
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









