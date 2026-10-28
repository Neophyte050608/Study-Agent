package io.github.imzmq.interview.knowledge.application.retrieval;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import io.github.imzmq.interview.search.application.contract.ToolGateway;

/**
 * 向量检索工具。
 * 
 * 职责：
 * 1. 封装 VectorStore：提供统一的 Query 接口，支持 topK 参数。
 * 2. 语义搜索：执行基于向量相似度的知识召回。
 */
@Component
public class VectorSearchTool implements ToolGateway<VectorSearchTool.Query, List<Document>> {

    private final ObjectProvider<VectorStore> vectorStoreProvider;

    public VectorSearchTool(ObjectProvider<VectorStore> vectorStoreProvider) {
        this.vectorStoreProvider = vectorStoreProvider;
    }

    @Override
    public List<Document> run(Query input) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            return List.of();
        }
        int topK = input.topK() == null || input.topK() < 1 ? 3 : input.topK();
        return vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(input.query())
                        .topK(topK)
                        .build()
        );
    }

    public record Query(String query, Integer topK) {
    }
}

