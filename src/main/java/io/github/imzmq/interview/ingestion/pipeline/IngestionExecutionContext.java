package io.github.imzmq.interview.ingestion.pipeline;

import io.github.imzmq.interview.ingestion.application.IngestionService;

import java.util.function.Supplier;

/**
 * 入库管道执行上下文。
 *
 * <p>该上下文对象用于在“阶段节点”之间传递共享数据与惰性计算能力：</p>
 * <ul>
 *     <li>同步提交器：真正执行入库落库（向量/词法/图谱/增量标记等）的同步函数</li>
 *     <li>计数供应器：用于统计 FETCH/PARSE/ENHANCE/CHUNK 阶段的输入输出规模</li>
 *     <li>当前计数：用于阶段间传递“上一阶段输出规模”，便于生成阶段日志</li>
 *     <li>摘要：同步提交后产生的 {@link IngestionService.SyncSummary}，用于任务层状态计算与回包</li>
 * </ul>
 *
 * <p>注意：PARSE/ENHANCE/CHUNK 的统计通常较重，因此通过 resolveXXX() 做懒加载并缓存结果，
 * 避免一个任务中被重复计算多次。</p>
 */
public class IngestionExecutionContext {

    /**
     * 执行真实入库落库动作的供应器（一般来自 {@link io.github.imzmq.interview.ingestion.application.IngestionService}）。
     */
    private final Supplier<IngestionService.SyncSummary> syncSupplier;
    /**
     * 统计“源文档数量”的供应器（FETCH 阶段输出）。
     */
    private final Supplier<Integer> sourceCountSupplier;
    /**
     * 统计“解析后文档数量”的供应器（PARSE 阶段输出）。
     */
    private final Supplier<Integer> parsedCountSupplier;
    /**
     * 统计“增强后文档数量”的供应器（ENHANCE 阶段输出）。
     */
    private final Supplier<Integer> enhancedCountSupplier;
    /**
     * 统计“切块数量”的供应器（CHUNK 阶段输出）。
     */
    private final Supplier<Integer> chunkCountSupplier;
    /**
     * 当前阶段所处的“规模计数”，用于阶段间传递与日志展示。
     */
    private int currentCount;
    /**
     * 同步提交后写入的入库摘要；只有在 SYNC_MARK/LEGACY_SYNC 等提交阶段才会被设置。
     */
    private IngestionService.SyncSummary summary;
    /**
     * PARSE 阶段统计缓存，避免重复计算。
     */
    private Integer parsedCount;
    /**
     * ENHANCE 阶段统计缓存，避免重复计算。
     */
    private Integer enhancedCount;
    /**
     * CHUNK 阶段统计缓存，避免重复计算。
     */
    private Integer chunkCount;

    public IngestionExecutionContext(
            Supplier<IngestionService.SyncSummary> syncSupplier,
            Supplier<Integer> sourceCountSupplier,
            Supplier<Integer> parsedCountSupplier,
            Supplier<Integer> enhancedCountSupplier,
            Supplier<Integer> chunkCountSupplier
    ) {
        this.syncSupplier = syncSupplier;
        this.sourceCountSupplier = sourceCountSupplier;
        this.parsedCountSupplier = parsedCountSupplier;
        this.enhancedCountSupplier = enhancedCountSupplier;
        this.chunkCountSupplier = chunkCountSupplier;
    }

    public Supplier<IngestionService.SyncSummary> getSyncSupplier() {
        return syncSupplier;
    }

    public Supplier<Integer> getSourceCountSupplier() {
        return sourceCountSupplier;
    }

    /**
     * 懒加载并返回 PARSE 阶段输出计数。
     *
     * @return 解析后的文档数量
     */
    public int resolveParsedCount() {
        if (parsedCount == null) {
            parsedCount = parsedCountSupplier.get();
        }
        return parsedCount;
    }

    /**
     * 懒加载并返回 CHUNK 阶段输出计数。
     *
     * @return 切块数量
     */
    public int resolveChunkCount() {
        if (chunkCount == null) {
            chunkCount = chunkCountSupplier.get();
        }
        return chunkCount;
    }

    /**
     * 懒加载并返回 ENHANCE 阶段输出计数。
     *
     * @return 增强后的文档数量
     */
    public int resolveEnhancedCount() {
        if (enhancedCount == null) {
            enhancedCount = enhancedCountSupplier.get();
        }
        return enhancedCount;
    }

    public int getCurrentCount() {
        return currentCount;
    }

    /**
     * 更新当前阶段计数，供下一阶段作为 inputCount 使用。
     *
     * @param currentCount 当前规模计数
     */
    public void setCurrentCount(int currentCount) {
        this.currentCount = currentCount;
    }

    public IngestionService.SyncSummary getSummary() {
        return summary;
    }

    /**
     * 设置同步提交后的摘要结果。
     *
     * <p>通常只在 SYNC_MARK/LEGACY_SYNC 这类“提交阶段”执行后设置。</p>
     *
     * @param summary 同步摘要
     */
    public void setSummary(IngestionService.SyncSummary summary) {
        this.summary = summary;
    }
}


