package io.github.imzmq.interview.rag.core;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 文档切分属性配置类。
 * 绑定 app.rag.chunking 前缀。
 */
@Configuration
@ConfigurationProperties(prefix = "app.rag.chunking")
public class ChunkingProperties {

    /** 分块策略 */
    private ChunkingStrategy strategy = ChunkingStrategy.STRUCTURE_RECURSIVE_WITH_METADATA;

    /** 目标切分 Token 大小（软限制，尽量在这个长度附近切分） */
    private int targetSize = 550;

    /** 最大 Token 大小（硬限制，超过此长度会强制切断） */
    private int maxSize = 800;

    /** 重叠 Token 大小 */
    private int overlap = 80;

    /** 解析 Markdown 时保留的标题层级深度（如 3 代表保留 #, ##, ###） */
    private int headingLevels = 3;

    /** 是否在每个 Chunk 的内容前面补充前缀（文档名、章节等元数据） */
    private boolean metadataPrefixEnabled = true;

    /** 当结构化/递归切分仍然超过 maxSize 时，是否回退到 Token 切分强制阻断 */
    private boolean fallbackTokenSplitEnabled = true;

    /** 补充前缀时，是否包含来源类型 (source_type) */
    private boolean includeSourceType = true;

    /** 补充前缀时，是否包含知识标签 (knowledge_tags) */
    private boolean includeKnowledgeTags = true;

    public ChunkingStrategy getStrategy() {
        return strategy;
    }

    public void setStrategy(ChunkingStrategy strategy) {
        this.strategy = strategy;
    }

    public int getTargetSize() {
        return targetSize;
    }

    public void setTargetSize(int targetSize) {
        this.targetSize = targetSize;
    }

    public int getMaxSize() {
        return maxSize;
    }

    public void setMaxSize(int maxSize) {
        this.maxSize = maxSize;
    }

    public int getOverlap() {
        return overlap;
    }

    public void setOverlap(int overlap) {
        this.overlap = overlap;
    }

    public int getHeadingLevels() {
        return headingLevels;
    }

    public void setHeadingLevels(int headingLevels) {
        this.headingLevels = headingLevels;
    }

    public boolean isMetadataPrefixEnabled() {
        return metadataPrefixEnabled;
    }

    public void setMetadataPrefixEnabled(boolean metadataPrefixEnabled) {
        this.metadataPrefixEnabled = metadataPrefixEnabled;
    }

    public boolean isFallbackTokenSplitEnabled() {
        return fallbackTokenSplitEnabled;
    }

    public void setFallbackTokenSplitEnabled(boolean fallbackTokenSplitEnabled) {
        this.fallbackTokenSplitEnabled = fallbackTokenSplitEnabled;
    }

    public boolean isIncludeSourceType() {
        return includeSourceType;
    }

    public void setIncludeSourceType(boolean includeSourceType) {
        this.includeSourceType = includeSourceType;
    }

    public boolean isIncludeKnowledgeTags() {
        return includeKnowledgeTags;
    }

    public void setIncludeKnowledgeTags(boolean includeKnowledgeTags) {
        this.includeKnowledgeTags = includeKnowledgeTags;
    }
}

