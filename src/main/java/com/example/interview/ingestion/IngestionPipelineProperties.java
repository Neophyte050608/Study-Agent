package com.example.interview.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "app.ingestion")
public class IngestionPipelineProperties {

    private List<Pipeline> pipelines = defaultPipelines();

    public List<Pipeline> getPipelines() {
        return pipelines;
    }

    public void setPipelines(List<Pipeline> pipelines) {
        this.pipelines = pipelines;
    }

    public static class Pipeline {
        private String name;
        private String sourceType;
        private boolean enabled = true;
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
