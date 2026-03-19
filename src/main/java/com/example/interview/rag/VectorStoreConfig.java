package com.example.interview.rag;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;

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
