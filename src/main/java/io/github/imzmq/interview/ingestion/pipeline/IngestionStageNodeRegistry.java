package io.github.imzmq.interview.ingestion.pipeline;

import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;

/**
 * 入库阶段节点注册表。
 *
 * <p>该注册表以“阶段枚举 -> 节点实现”的方式组织入库管道的各阶段逻辑。
 * 当前实现以最小可用为目标：前置阶段（FETCH/PARSE/ENHANCE/CHUNK）返回真实计数，
 * 中间阶段（EMBED_INDEX/LEXICAL_INDEX/GRAPH_SYNC）暂以透传节点表示，
 * 提交阶段（SYNC_MARK/LEGACY_SYNC）会执行真实同步落库并产出摘要。</p>
 *
 * <p>后续如果需要更强的可插拔能力，可以将节点实现拆分为独立类并注入依赖，而不是在注册表中以内部类硬编码。</p>
 */
@Service
public class IngestionStageNodeRegistry {

    private final Map<IngestionNodeStage, IngestionStageNode> nodes;

    public IngestionStageNodeRegistry() {
        Map<IngestionNodeStage, IngestionStageNode> map = new EnumMap<>(IngestionNodeStage.class);
        register(map, new FetchStageNode());
        register(map, new ParseStageNode());
        register(map, new EnhanceStageNode());
        register(map, new ChunkStageNode());
        register(map, new PassThroughStageNode(IngestionNodeStage.EMBED_INDEX, "embedding-indexed"));
        register(map, new PassThroughStageNode(IngestionNodeStage.LEXICAL_INDEX, "lexical-indexed"));
        register(map, new PassThroughStageNode(IngestionNodeStage.GRAPH_SYNC, "graph-synced"));
        register(map, new SyncCommitStageNode(IngestionNodeStage.SYNC_MARK));
        register(map, new SyncCommitStageNode(IngestionNodeStage.LEGACY_SYNC));
        this.nodes = map;
    }

    /**
     * 获取指定阶段对应的节点实现。
     *
     * @param stage 阶段枚举
     * @return 阶段节点实现
     */
    public IngestionStageNode require(IngestionNodeStage stage) {
        IngestionStageNode node = nodes.get(stage);
        if (node == null) {
            throw new IllegalStateException("未找到阶段节点实现: " + stage);
        }
        return node;
    }

    private void register(Map<IngestionNodeStage, IngestionStageNode> map, IngestionStageNode node) {
        map.put(node.stage(), node);
    }

    /**
     * FETCH 阶段：统计源数据规模。
     *
     * <p>对于本地目录入库，通常等价于“扫描到的 Markdown 文件数量”；
     * 对于上传入库，通常等价于“满足条件的上传 Markdown 文件数量”。</p>
     */
    private static final class FetchStageNode implements IngestionStageNode {
        @Override
        public IngestionNodeStage stage() {
            return IngestionNodeStage.FETCH;
        }

        @Override
        public IngestionStageResult execute(IngestionExecutionContext context) {
            int output = context.getSourceCountSupplier().get();
            context.setCurrentCount(output);
            return new IngestionStageResult(0, output, "source-loaded", Map.of("sourceDocuments", output));
        }
    }

    /**
     * PARSE 阶段：统计解析后的文档数量。
     *
     * <p>该阶段不做真实落库，仅用于提供可观测的规模指标，便于判断解析是否异常（例如全部解析为 0）。</p>
     */
    private static final class ParseStageNode implements IngestionStageNode {
        @Override
        public IngestionNodeStage stage() {
            return IngestionNodeStage.PARSE;
        }

        @Override
        public IngestionStageResult execute(IngestionExecutionContext context) {
            int input = context.getCurrentCount();
            int output = context.resolveParsedCount();
            context.setCurrentCount(output);
            return new IngestionStageResult(input, output, "parsed-real", Map.of("parsedDocuments", output));
        }
    }

    /**
     * ENHANCE 阶段：统计增强后的文档数量。
     *
     * <p>当前实现以统计为主：如果增强逻辑不改变文档条数，则 output 可能等于 input。</p>
     */
    private static final class EnhanceStageNode implements IngestionStageNode {
        @Override
        public IngestionNodeStage stage() {
            return IngestionNodeStage.ENHANCE;
        }

        @Override
        public IngestionStageResult execute(IngestionExecutionContext context) {
            int input = context.getCurrentCount();
            int output = context.resolveEnhancedCount();
            context.setCurrentCount(output);
            return new IngestionStageResult(input, output, "enhanced-real", Map.of("enhancedDocuments", output));
        }
    }

    /**
     * CHUNK 阶段：统计切块数量。
     *
     * <p>切块数量通常大于等于解析文档数量，是检索与向量化的核心规模指标。</p>
     */
    private static final class ChunkStageNode implements IngestionStageNode {
        @Override
        public IngestionNodeStage stage() {
            return IngestionNodeStage.CHUNK;
        }

        @Override
        public IngestionStageResult execute(IngestionExecutionContext context) {
            int input = context.getCurrentCount();
            int output = context.resolveChunkCount();
            context.setCurrentCount(output);
            return new IngestionStageResult(input, output, "chunked-real", Map.of("chunks", output));
        }
    }

    /**
     * 透传阶段：保持计数不变，仅生成阶段日志。
     *
     * <p>用于暂未实现“真实节点逻辑”的阶段占位（例如索引写入/图谱同步）。
     * 真实落库目前由提交阶段统一触发，因此这些阶段的输出计数保持与输入一致。</p>
     */
    private static final class PassThroughStageNode implements IngestionStageNode {
        private final IngestionNodeStage stage;
        private final String message;

        private PassThroughStageNode(IngestionNodeStage stage, String message) {
            this.stage = stage;
            this.message = message;
        }

        @Override
        public IngestionNodeStage stage() {
            return stage;
        }

        @Override
        public IngestionStageResult execute(IngestionExecutionContext context) {
            int count = context.getCurrentCount();
            return IngestionStageResult.of(count, count, message);
        }
    }

    /**
     * 提交阶段：执行真实同步落库，并产出入库摘要。
     *
     * <p>该阶段会调用上下文中的 syncSupplier 执行 {@link io.github.imzmq.interview.ingestion.application.IngestionService}
     * 的同步逻辑（向量/词法/图谱/增量标记等），并将摘要写回上下文供任务层生成最终状态。</p>
     */
    private static final class SyncCommitStageNode implements IngestionStageNode {
        private final IngestionNodeStage stage;

        private SyncCommitStageNode(IngestionNodeStage stage) {
            this.stage = stage;
        }

        @Override
        public IngestionNodeStage stage() {
            return stage;
        }

        @Override
        public IngestionStageResult execute(IngestionExecutionContext context) {
            int input = context.getCurrentCount();
            var summary = context.getSyncSupplier().get();
            context.setSummary(summary);
            int output = summary.newFiles + summary.modifiedFiles;
            context.setCurrentCount(output);
            return new IngestionStageResult(
                    input,
                    output,
                    "sync-committed",
                    Map.of(
                            "newFiles", summary.newFiles,
                            "modifiedFiles", summary.modifiedFiles,
                            "failedFiles", summary.failedFiles,
                            "deletedFiles", summary.deletedFiles
                    )
            );
        }
    }
}


