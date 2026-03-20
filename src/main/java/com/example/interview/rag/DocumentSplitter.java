package com.example.interview.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文档切分器。
 * 负责将长文档切分成适合向量化和 LLM 处理的块（Chunks）。
 */
@Component
public class DocumentSplitter {

    /** 使用 TokenTextSplitter 以获得更精准的上下文管理 */
    private final TokenTextSplitter tokenTextSplitter;

    public DocumentSplitter() {
        // 默认配置：
        // 1. 每块 800 个 token
        // 2. 相邻块重叠 350 个 token (保证语义连续性)
        // 3. 最小字符数 5，最大迭代次数 10000
        // 4. 保留分隔符 (true)
        this.tokenTextSplitter = new TokenTextSplitter(800, 350, 5, 10000, true);
    }

    /**
     * 执行切分操作。
     * 
     * @param documents 原始文档列表
     * @return 切分后的子文档列表
     */
    public List<Document> split(List<Document> documents) {
        return this.tokenTextSplitter.apply(documents);
    }
}
