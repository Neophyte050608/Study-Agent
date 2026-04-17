package io.github.imzmq.interview.ingestion.pipeline;

/**
 * 入库管道的阶段枚举。
 *
 * <p>该枚举用于描述一次知识入库（本地目录/浏览器上传）从“源数据加载”到“索引落库/同步标记”的完整流水线步骤。
 * 管道定义由 {@link IngestionPipelineDefinition#stages()} 给出，执行由 {@link IngestionTaskService} 串行推进。</p>
 */
public enum IngestionNodeStage {
    /**
     * 获取源数据（本地扫描或上传文件列表），用于统计/预热阶段。
     */
    FETCH,
    /**
     * 解析阶段：将 Markdown 等源内容解析为结构化文档（例如按文件、标题等）。
     */
    PARSE,
    /**
     * 增强阶段：对解析后的文档做补全/清洗/提取元数据等增强处理。
     */
    ENHANCE,
    /**
     * 切块阶段：将文档切分为适合检索/向量化的小片段（chunk），并保留元数据。
     */
    CHUNK,
    /**
     * 向量索引阶段：将 chunk 写入向量库（embedding + upsert）。
     */
    EMBED_INDEX,
    /**
     * 词法索引阶段：将 chunk 文本写入词法索引（如 MySQL FULLTEXT/LIKE）。
     */
    LEXICAL_INDEX,
    /**
     * 图谱同步阶段：将关系/链接等写入图数据库或图结构索引（GraphRAG）。
     */
    GRAPH_SYNC,
    /**
     * 同步标记阶段：入库成功后写入“同步索引/增量状态”，用于后续增量扫描判断是否变更。
     */
    SYNC_MARK,
    /**
     * 兼容旧链路的同步提交阶段：直接执行传统的 sync 落库逻辑并提交摘要。
     *
     * <p>该阶段一般作为兜底或兼容入口使用，避免管道配置不完整导致无法落库。</p>
     */
    LEGACY_SYNC
}

