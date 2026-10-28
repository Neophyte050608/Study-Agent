package io.github.imzmq.interview.modelrouting.application;

import io.github.imzmq.interview.modelrouting.core.ModelRoutingCandidate;
import io.github.imzmq.interview.modelrouting.registry.DynamicChatModelRegistry;
import io.github.imzmq.interview.modelrouting.registry.ModelBeanResolver;
import io.github.imzmq.interview.modelrouting.security.ApiKeyEncryptor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DynamicModelFactoryTest {

    private static final String OPENAI_KEY_PROPERTY = "spring.ai.openai.api-key";

    private final ApplicationContext applicationContext = mock(ApplicationContext.class);
    private final ModelBeanResolver modelBeanResolver = mock(ModelBeanResolver.class);
    private final DynamicChatModelRegistry registry = mock(DynamicChatModelRegistry.class);
    private final ApiKeyEncryptor apiKeyEncryptor = mock(ApiKeyEncryptor.class);
    private final Environment environment = mock(Environment.class);

    @Test
    void usesEncryptedCandidateKeyWhenPresent() {
        ChatModel chatModel = mock(ChatModel.class);
        ModelRoutingCandidate candidate = dynamicCandidate("deepseek-chat", "openai", "encrypted-key-ref");
        when(registry.getIfPresent("deepseek-chat")).thenReturn(null);
        when(apiKeyEncryptor.decrypt("encrypted-key-ref")).thenReturn("sk-candidate");
        when(registry.getOrCreate("deepseek-chat", "https://api.deepseek.com", "sk-candidate", "deepseek-chat"))
                .thenReturn(chatModel);

        ChatModel result = factory().getByCandidate(candidate);

        assertSame(chatModel, result);
        verify(apiKeyEncryptor).decrypt("encrypted-key-ref");
        verifyNoInteractions(environment);
    }

    @Test
    void fallsBackToSpringOpenAiKeyForOpenAiCompatibleDynamicCandidate() {
        ChatModel chatModel = mock(ChatModel.class);
        ModelRoutingCandidate candidate = dynamicCandidate("deepseek-chat", "openai", "");
        when(registry.getIfPresent("deepseek-chat")).thenReturn(null);
        when(environment.getProperty(OPENAI_KEY_PROPERTY, "")).thenReturn("sk-private");
        when(registry.getOrCreate("deepseek-chat", "https://api.deepseek.com", "sk-private", "deepseek-chat"))
                .thenReturn(chatModel);

        ChatModel result = factory().getByCandidate(candidate);

        assertSame(chatModel, result);
        verify(environment).getProperty(OPENAI_KEY_PROPERTY, "");
        verifyNoInteractions(apiKeyEncryptor);
    }

    @Test
    void doesNotUseOpenAiFallbackForNonOpenAiProvider() {
        ChatModel chatModel = mock(ChatModel.class);
        ModelRoutingCandidate candidate = dynamicCandidate("dashscope-chat", "dashscope", "");
        when(registry.getIfPresent("dashscope-chat")).thenReturn(null);
        when(registry.getOrCreate("dashscope-chat", "https://api.deepseek.com", "", "deepseek-chat"))
                .thenReturn(chatModel);

        ChatModel result = factory().getByCandidate(candidate);

        assertSame(chatModel, result);
        verifyNoInteractions(environment);
        verifyNoInteractions(apiKeyEncryptor);
    }

    @Test
    void returnsCachedDynamicModelWithoutResolvingApiKey() {
        ChatModel chatModel = mock(ChatModel.class);
        ModelRoutingCandidate candidate = dynamicCandidate("deepseek-chat", "openai", "");
        when(registry.getIfPresent("deepseek-chat")).thenReturn(chatModel);

        ChatModel result = factory().getByCandidate(candidate);

        assertSame(chatModel, result);
        verifyNoInteractions(environment);
        verifyNoInteractions(apiKeyEncryptor);
    }

    private DynamicModelFactory factory() {
        return new DynamicModelFactory(applicationContext, modelBeanResolver, registry, apiKeyEncryptor, environment);
    }

    private ModelRoutingCandidate dynamicCandidate(String name, String provider, String apiKeyRef) {
        return new ModelRoutingCandidate(
                name,
                provider,
                "deepseek-chat",
                "",
                10,
                false,
                "https://api.deepseek.com",
                apiKeyRef,
                "GENERAL",
                "TEST"
        );
    }
}
