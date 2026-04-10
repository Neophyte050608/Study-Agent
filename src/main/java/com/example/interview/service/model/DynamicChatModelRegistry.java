package com.example.interview.service.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DynamicChatModelRegistry {

    private static final Logger logger = LoggerFactory.getLogger(DynamicChatModelRegistry.class);

    private final ConcurrentHashMap<String, ChatModel> modelCache = new ConcurrentHashMap<>();

    public ChatModel getIfPresent(String candidateName) {
        if (candidateName == null || candidateName.isBlank()) {
            return null;
        }
        return modelCache.get(candidateName);
    }

    public ChatModel getOrCreate(String candidateName, String baseUrl, String apiKey, String modelId) {
        return modelCache.computeIfAbsent(candidateName, ignored -> createChatModel(candidateName, baseUrl, apiKey, modelId));
    }

    public void evict(String candidateName) {
        ChatModel removed = modelCache.remove(candidateName);
        if (removed != null) {
            logger.info("已清除动态 ChatModel 缓存: {}", candidateName);
        }
    }

    public void evictAll() {
        modelCache.clear();
        logger.info("已清除全部动态 ChatModel 缓存");
    }

    public int cacheSize() {
        return modelCache.size();
    }

    private ChatModel createChatModel(String candidateName, String baseUrl, String apiKey, String modelId) {
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        logger.info("创建动态 ChatModel: name={}, baseUrl={}, model={}", candidateName, normalizedBaseUrl, modelId);
        OpenAiApi openAiApi = buildOpenAiApi(normalizedBaseUrl, apiKey);
        OpenAiChatOptions options = OpenAiChatOptions.builder().model(modelId).build();
        try {
            Method builderMethod = OpenAiChatModel.class.getMethod("builder");
            Object builder = builderMethod.invoke(null);
            builder.getClass().getMethod("openAiApi", OpenAiApi.class).invoke(builder, openAiApi);
            builder.getClass().getMethod("defaultOptions", OpenAiChatOptions.class).invoke(builder, options);
            return (ChatModel) builder.getClass().getMethod("build").invoke(builder);
        } catch (NoSuchMethodException ex) {
            return instantiateByConstructor(openAiApi, options);
        } catch (Exception ex) {
            throw new IllegalStateException("创建动态 ChatModel 失败: " + candidateName, ex);
        }
    }

    private OpenAiApi buildOpenAiApi(String baseUrl, String apiKey) {
        try {
            Method builderMethod = OpenAiApi.class.getMethod("builder");
            Object builder = builderMethod.invoke(null);
            builder.getClass().getMethod("baseUrl", String.class).invoke(builder, baseUrl);
            builder.getClass().getMethod("apiKey", String.class).invoke(builder, apiKey);
            return (OpenAiApi) builder.getClass().getMethod("build").invoke(builder);
        } catch (NoSuchMethodException ex) {
            return instantiateOpenAiApi(baseUrl, apiKey);
        } catch (Exception ex) {
            throw new IllegalStateException("创建 OpenAiApi 失败", ex);
        }
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/v1")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        }
        return normalized;
    }

    private OpenAiApi instantiateOpenAiApi(String baseUrl, String apiKey) {
        try {
            Constructor<OpenAiApi> constructor = OpenAiApi.class.getConstructor(String.class, String.class);
            return constructor.newInstance(baseUrl, apiKey);
        } catch (Exception ex) {
            throw new IllegalStateException("当前 Spring AI 版本不支持动态创建 OpenAiApi", ex);
        }
    }

    private ChatModel instantiateByConstructor(OpenAiApi openAiApi, OpenAiChatOptions options) {
        for (Constructor<?> constructor : OpenAiChatModel.class.getConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length >= 2
                    && OpenAiApi.class.isAssignableFrom(parameterTypes[0])
                    && OpenAiChatOptions.class.isAssignableFrom(parameterTypes[1])) {
                Object[] args = new Object[parameterTypes.length];
                args[0] = openAiApi;
                args[1] = options;
                try {
                    return (ChatModel) constructor.newInstance(args);
                } catch (Exception ex) {
                    throw new IllegalStateException("通过构造器创建 OpenAiChatModel 失败", ex);
                }
            }
        }
        throw new IllegalStateException("当前 Spring AI 版本不支持动态创建 OpenAiChatModel");
    }
}
