package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

/**
 * 语料库中的单个 chunk，对应 Saber test.txt 的一行记录。
 *
 * <p>仅保留评测所需的核心字段；其余元数据(作者、时间、sha256 等)在解析时忽略，
 * 以降低内存占用并聚焦检索评测口径。</p>
 *
 * @param chunkId    chunk 唯一标识，例如 {@code juejin_7652563235686350867_00000}
 * @param documentId 所属文档 ID
 * @param chunkIndex chunk 在文档内的顺序
 * @param content    chunk 正文内容
 * @param title      文档标题
 * @param category   分类标签
 * @param url        来源 URL
 */
public record GoldSetChunk(
        String chunkId,
        String documentId,
        int chunkIndex,
        String content,
        String title,
        String category,
        String url
) {
}
