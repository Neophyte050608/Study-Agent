package io.github.imzmq.interview.rag.core;

/**
 * 分块策略枚举。
 */
public enum ChunkingStrategy {
    /** 基于 Markdown 结构进行分段，再在内部进行递归字符切分 */
    STRUCTURE_RECURSIVE,
    
    /** 在 STRUCTURE_RECURSIVE 的基础上，为每个 chunk 头部注入文档元数据 (标题、标签等) */
    STRUCTURE_RECURSIVE_WITH_METADATA
}

