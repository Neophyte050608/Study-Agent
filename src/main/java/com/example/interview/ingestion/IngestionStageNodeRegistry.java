package com.example.interview.ingestion;

import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;

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
