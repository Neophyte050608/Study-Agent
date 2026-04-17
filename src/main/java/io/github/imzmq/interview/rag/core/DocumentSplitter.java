package io.github.imzmq.interview.rag.core;

import io.github.imzmq.interview.config.knowledge.ParentChildRetrievalProperties;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文档切分器。
 * 负责将长文档切分成适合向量化和 LLM 处理的块（Chunks）。
 * 升级版：支持结构化优先、递归字符切分与元数据增强组合策略。
 */
@Component
public class DocumentSplitter {

    private final ChunkingProperties properties;
    private final ParentChildRetrievalProperties parentChildProperties;

    public DocumentSplitter(ChunkingProperties properties, ParentChildRetrievalProperties parentChildProperties) {
        this.properties = properties;
        this.parentChildProperties = parentChildProperties;
    }

    /**
     * 执行切分操作。
     * 
     * @param documents 原始文档列表
     * @return 切分后的子文档列表
     */
    public List<Document> split(List<Document> documents) {
        List<Document> result = new ArrayList<>();
        int childTargetSize = parentChildProperties.isEnabled() ? parentChildProperties.getChildTargetSize() : properties.getTargetSize();
        int childOverlap = parentChildProperties.isEnabled() ? parentChildProperties.getChildOverlap() : properties.getOverlap();
        RecursiveChunker recursiveChunker = new RecursiveChunker(
                childTargetSize,
                properties.getMaxSize(),
                childOverlap,
                properties.isFallbackTokenSplitEnabled()
        );

        for (Document doc : documents) {
            String text = doc.getText();
            if (text == null || text.isBlank()) {
                continue;
            }

            // 1. 基于 Markdown 结构切分为 Sections
            List<MarkdownSection> sections = MarkdownSectionBuilder.buildSections(text, properties.getHeadingLevels());

            int chunkIndex = 0;
            for (MarkdownSection section : sections) {
                String parentId = UUID.randomUUID().toString();
                String parentText = safeParentText(section.getContent());
                // 2. 对每个 Section 执行递归切分
                List<String> textChunks = recursiveChunker.split(section.getContent());

                for (String chunkText : textChunks) {
                    if (chunkText.isBlank()) {
                        continue;
                    }

                    // 3. 元数据增强
                    String enrichedText = chunkText;
                    if (properties.getStrategy() == ChunkingStrategy.STRUCTURE_RECURSIVE_WITH_METADATA && properties.isMetadataPrefixEnabled()) {
                        enrichedText = enrichChunkContext(chunkText, section, doc.getMetadata());
                    }

                    Document chunkDoc = new Document(enrichedText);
                    // 继承原始元数据
                    chunkDoc.getMetadata().putAll(doc.getMetadata());
                    // 补充新元数据
                    chunkDoc.getMetadata().put("section_path", section.getFormattedPath());
                    chunkDoc.getMetadata().put("chunk_index", chunkIndex++);
                    chunkDoc.getMetadata().put("chunk_strategy", properties.getStrategy().name());
                    chunkDoc.getMetadata().put("parent_id", parentId);
                    chunkDoc.getMetadata().put("parent_text", parentText);
                    chunkDoc.getMetadata().put("child_id", UUID.randomUUID().toString());
                    chunkDoc.getMetadata().put("child_index", chunkIndex - 1);

                    result.add(chunkDoc);
                }
            }
        }

        return result;
    }

    private String enrichChunkContext(String chunkText, MarkdownSection section, Map<String, Object> metadata) {
        StringBuilder prefix = new StringBuilder();
        
        // 提取文档标题（优先从元数据获取，若无则尝试从路径解析）
        String filePath = (String) metadata.get("file_path");
        String docTitle = extractDocTitle(filePath);
        if (docTitle != null && !docTitle.isBlank()) {
            prefix.append("[文档: ").append(docTitle).append("] ");
        }

        // 注入章节路径
        String sectionPath = section.getFormattedPath();
        if (sectionPath != null && !sectionPath.isBlank() && !"Root".equals(sectionPath)) {
            prefix.append("[章节: ").append(sectionPath).append("] ");
        }

        // 注入来源类型
        if (properties.isIncludeSourceType()) {
            String sourceType = (String) metadata.get("source_type");
            if (sourceType != null && !sourceType.isBlank()) {
                prefix.append("[来源: ").append(sourceType).append("] ");
            }
        }

        // 注入知识标签
        if (properties.isIncludeKnowledgeTags()) {
            String tags = (String) metadata.get("knowledge_tags");
            if (tags != null && !tags.isBlank()) {
                prefix.append("[标签: ").append(tags).append("] ");
            }
        }

        if (prefix.length() > 0) {
            return prefix.toString().trim() + "\n" + chunkText;
        }
        return chunkText;
    }

    private String extractDocTitle(String filePath) {
        if (filePath == null) return null;
        String fileName = java.nio.file.Path.of(filePath).getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private String safeParentText(String text) {
        if (text == null) {
            return "";
        }
        int max = Math.max(200, parentChildProperties.getParentMaxSize());
        return text.length() > max ? text.substring(0, max) : text;
    }
}


