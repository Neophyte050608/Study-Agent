package com.example.interview.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 入库管道配置（Spring Boot 配置绑定）。
 *
 * <p>配置前缀：{@code app.ingestion}。支持为不同 sourceType 配置独立的 stages 顺序与启停开关，
 * 以便在不改代码的情况下调整入库链路（例如临时关闭图谱同步阶段）。</p>
 *
 * <p>当外部未提供 pipelines 配置时，会使用 {@link #defaultPipelines()} 提供的默认管道，
 * 覆盖本地目录与浏览器上传两种数据源。</p>
 */
@Component
@ConfigurationProperties(prefix = "app.ingestion")
public class IngestionPipelineProperties {

    /**
     * 管道列表配置。
     *
     * <p>注意：这里初始化为默认管道，确保开发/测试环境在未配置时也能运行入库链路。</p>
     */
    private List<Pipeline> pipelines = defaultPipelines();

    public List<Pipeline> getPipelines() {
        return pipelines;
    }

    public void setPipelines(List<Pipeline> pipelines) {
        this.pipelines = pipelines;
    }

    public static class Pipeline {
        /**
         * 管道名称（用于展示与观测），可为空；为空时会自动生成 pipeline-{sourceType}。
         */
        private String name;
        /**
         * 数据源类型（例如 LOCAL_VAULT / BROWSER_UPLOAD）。
         */
        private String sourceType;
        /**
         * 是否启用该管道；禁用时不允许执行入库任务。
         */
        private boolean enabled = true;
        /**
         * 阶段列表（串行执行顺序）。
         */
        private List<IngestionNodeStage> stages = new ArrayList<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getSourceType() {
            return sourceType;
        }

        public void setSourceType(String sourceType) {
            this.sourceType = sourceType;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<IngestionNodeStage> getStages() {
            return stages;
        }

        public void setStages(List<IngestionNodeStage> stages) {
            this.stages = stages;
        }
    }

    private List<Pipeline> defaultPipelines() {
        // 默认阶段序列：从源加载 -> 解析/增强/切块 -> 索引写入 -> 图谱同步 -> 增量标记
        List<IngestionNodeStage> defaultStages = List.of(
                IngestionNodeStage.FETCH,
                IngestionNodeStage.PARSE,
                IngestionNodeStage.ENHANCE,
                IngestionNodeStage.CHUNK,
                IngestionNodeStage.EMBED_INDEX,
                IngestionNodeStage.LEXICAL_INDEX,
                IngestionNodeStage.GRAPH_SYNC,
                IngestionNodeStage.SYNC_MARK
        );
        Pipeline local = new Pipeline();
        local.setName("legacy-default-pipeline-local");
        local.setSourceType("LOCAL_VAULT");
        local.setEnabled(true);
        local.setStages(new ArrayList<>(defaultStages));

        Pipeline upload = new Pipeline();
        upload.setName("legacy-default-pipeline-upload");
        upload.setSourceType("BROWSER_UPLOAD");
        upload.setEnabled(true);
        upload.setStages(new ArrayList<>(defaultStages));

        List<Pipeline> defaults = new ArrayList<>();
        defaults.add(local);
        defaults.add(upload);
        return defaults;
    }
}
