package com.example.interview.rag;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;

/**
 * 向量库（Milvus）相关配置入口。
 *
 * <p>当前项目主要依赖 Spring AI 的自动装配来创建 {@link VectorStore}（例如 {@link MilvusVectorStore}）：
 * 向量库连接信息、collection 名称等通常由 {@code application.yml} 的 Spring AI/Milvus 配置提供。</p>
 *
 * <p>该类的意义在于保留“显式 Bean 自定义”的扩展点：</p>
 * <ul>
 *     <li>当需要自定义 MilvusClient 的连接参数、超时、重试等行为时</li>
 *     <li>当需要替换/包装 {@link EmbeddingModel} 的调用策略时</li>
 *     <li>当自动装配无法满足特定 collection / schema 的初始化需求时</li>
 * </ul>
 *
 * <p>注意：当前示例 Bean 为注释状态，表示默认情况下优先走自动装配，避免重复定义导致 Bean 冲突。</p>
 */
@Configuration
public class VectorStoreConfig {

    // Spring AI 通常会自动装配 VectorStore；如果需要更强的自定义能力，可以在这里显式声明 Bean。
    //
    // 示例：当自动装配不足以满足需求时，可显式定义 MilvusVectorStore Bean：
    /*
    @Bean
    public VectorStore vectorStore(MilvusServiceClient milvusClient, EmbeddingModel embeddingModel) {
        return new MilvusVectorStore(milvusClient, embeddingModel, 
            MilvusVectorStore.MilvusVectorStoreConfig.builder()
                .withCollectionName("interview_notes")
                .build());
    }
    */
}
