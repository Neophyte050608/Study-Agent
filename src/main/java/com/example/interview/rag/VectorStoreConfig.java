package com.example.interview.rag;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;

/**
 * 向量库配置类。
 * 负责初始化向量数据库（如 Milvus）的连接与配置。
 * 
 * 目前该项目主要依赖 Spring AI 的自动装配功能（通过 application.yml 中的配置）。
 * 如果需要自定义 Collection 名、索引类型或 Embedding 模型映射，可在此类中显式定义 Bean。
 */
@Configuration
public class VectorStoreConfig {

    // Spring AI auto-configuration usually handles this, but we can customize if needed.
    // For now, we rely on the starter to create the MilvusVectorStore bean using the properties in application.yml.
    // If we needed to customize the Milvus client or the EmbeddingModel usage, we would do it here.
    
    // Example of explicit bean definition if auto-config is not sufficient:
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
