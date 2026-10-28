package io.github.imzmq.interview.modelrouting.application;

import io.github.imzmq.interview.modelrouting.core.ModelRoutingCandidate;
import io.github.imzmq.interview.modelrouting.security.ApiKeyEncryptor;
import io.github.imzmq.interview.modelrouting.registry.DynamicChatModelRegistry;
import io.github.imzmq.interview.modelrouting.registry.ModelBeanResolver;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
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
    private static final String OPENAI_API_KEY_PROPERTY = "spring.ai.openai.api-key";

    private final DynamicChatModelRegistry dynamicChatModelRegistry;
    private final ApiKeyEncryptor apiKeyEncryptor;
    private final Environment environment;

    public DynamicModelFactory(ApplicationContext applicationContext,
                               ModelBeanResolver modelBeanResolver,
                               DynamicChatModelRegistry dynamicChatModelRegistry,
                               ApiKeyEncryptor apiKeyEncryptor,
                               Environment environment) {
        this.applicationContext = applicationContext;
        this.modelBeanResolver = modelBeanResolver;
        this.dynamicChatModelRegistry = dynamicChatModelRegistry;
        this.apiKeyEncryptor = apiKeyEncryptor;
        this.environment = environment;
    }

    public ChatModel getByCandidate(ModelRoutingCandidate candidate) {
        if (candidate.baseUrl() != null && !candidate.baseUrl().isBlank()) {
            ChatModel cachedModel = dynamicChatModelRegistry.getIfPresent(candidate.name());
            if (cachedModel != null) {
                return cachedModel;
            }
            String apiKey = resolveApiKey(candidate);
            return dynamicChatModelRegistry.getOrCreate(
                    candidate.name(),
                    candidate.baseUrl(),
                    apiKey,
                    candidate.model()
            );
        }
        return getByBeanNameOrDefault(modelBeanResolver.resolveByRoutingCandidate(candidate.provider(), candidate.beanName()));
    }

    private String resolveApiKey(ModelRoutingCandidate candidate) {
        if (candidate.apiKeyRef() != null && !candidate.apiKeyRef().isBlank()) {
            return apiKeyEncryptor.decrypt(candidate.apiKeyRef());
        }
        if ("openai".equalsIgnoreCase(candidate.provider())) {
            return environment.getProperty(OPENAI_API_KEY_PROPERTY, "");
        }
        return "";
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
