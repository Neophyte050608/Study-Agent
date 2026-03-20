package com.example.interview.tool;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 向量检索工具。
 * 
 * 职责：
 * 1. 封装 VectorStore：提供统一的 Query 接口，支持 topK 参数。
 * 2. 语义搜索：执行基于向量相似度的知识召回。
 */
@Component
public class VectorSearchTool implements ToolGateway<VectorSearchTool.Query, List<Document>> {

    private final VectorStore vectorStore;

    public VectorSearchTool(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public List<Document> run(Query input) {
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
