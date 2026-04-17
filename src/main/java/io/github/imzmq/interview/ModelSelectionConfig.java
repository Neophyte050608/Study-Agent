package io.github.imzmq.interview;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 模型选择与主 Bean 配置。
 * 
 * 职责：
 * 1. 统一注入：在存在多个模型（如 OpenAI, ZhipuAI, Ollama）时，通过 @Primary 指定系统默认使用的核心模型。
 * 2. 隔离配置：方便在开发/测试环境快速切换底层的 ChatModel 和 EmbeddingModel 实现。
 */
@Configuration
public class ModelSelectionConfig {

    @Bean
    @Primary
    public ChatModel primaryChatModel(@Qualifier("openAiChatModel") ChatModel chatModel) {
        return chatModel;
    }

    @Bean
    @Primary
    public EmbeddingModel primaryEmbeddingModel(@Qualifier("zhiPuAiEmbeddingModel") EmbeddingModel embeddingModel) {
        return embeddingModel;
    }
}

