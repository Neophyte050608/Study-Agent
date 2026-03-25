package com.example.interview.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DocumentSplitterTest {

    private ChunkingProperties properties;
    private DocumentSplitter splitter;

    @BeforeEach
    void setUp() {
        properties = new ChunkingProperties();
        properties.setStrategy(ChunkingStrategy.STRUCTURE_RECURSIVE_WITH_METADATA);
        properties.setTargetSize(100); // 调小限制以触发切分
        properties.setMaxSize(150);
        properties.setOverlap(20);
        properties.setHeadingLevels(3);
        properties.setMetadataPrefixEnabled(true);
        splitter = new DocumentSplitter(properties);
    }

    @Test
    void testMarkdownStructureChunking() {
        String markdown = """
                # 集合框架
                这是关于Java集合框架的介绍。

                ## HashMap
                HashMap基于数组+链表+红黑树实现。
                它的扩容机制是当元素个数超过阈值时触发。

                ### 扩容细节
                在JDK1.8中，链表长度超过8且数组长度超过64时转为红黑树。
                """;

        Document doc = new Document(markdown);
        doc.getMetadata().put("file_path", "java/collections.md");
        doc.getMetadata().put("knowledge_tags", "技术栈,八股");

        List<Document> chunks = splitter.split(List.of(doc));

        // 预期按标题分出至少3个有意义的块
        assertTrue(chunks.size() >= 3);
        
        // 检查元数据注入情况
        boolean foundHashMapChunk = false;
        for (Document chunk : chunks) {
            String text = chunk.getText();
            assertTrue(text.contains("[文档: collections]"));
            assertTrue(text.contains("[标签: 技术栈,八股]"));
            
            if (text.contains("红黑树实现")) {
                assertTrue(text.contains("[章节: 集合框架 > HashMap]"));
                foundHashMapChunk = true;
            }
        }
        assertTrue(foundHashMapChunk);
    }

    @Test
    void testFallbackTokenStrategy() {
        properties.setStrategy(ChunkingStrategy.TOKEN_ONLY);
        properties.setTargetSize(100);
        properties.setOverlap(10);
        splitter = new DocumentSplitter(properties);

        String text = "A".repeat(1000); // 超长文本，保证大于 100 Token
        Document doc = new Document(text);
        doc.getMetadata().put("file_path", "test.md");

        List<Document> chunks = splitter.split(List.of(doc));
        // 使用旧版切分器，应该被硬切为多块
        assertTrue(chunks.size() > 1);
        // 不会有 metadata 注入
        assertFalse(chunks.get(0).getText().contains("[文档: test]"));
    }
}
