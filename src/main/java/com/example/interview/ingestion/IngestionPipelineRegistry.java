package com.example.interview.ingestion;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class IngestionPipelineRegistry {

    private final IngestionPipelineProperties properties;

    public IngestionPipelineRegistry(IngestionPipelineProperties properties) {
        this.properties = properties;
    }

    public IngestionPipelineDefinition resolveRequiredBySource(String sourceType) {
        String normalized = normalizeSourceType(sourceType);
        return listPipelines().stream()
                .filter(item -> item.sourceType().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未找到匹配的数据源管道: " + normalized));
    }

    public List<IngestionPipelineDefinition> listPipelines() {
        List<IngestionPipelineDefinition> result = new ArrayList<>();
        for (IngestionPipelineProperties.Pipeline pipeline : properties.getPipelines()) {
            if (pipeline == null) {
                continue;
            }
            String sourceType = normalizeSourceType(pipeline.getSourceType());
            String name = pipeline.getName() == null || pipeline.getName().isBlank()
                    ? "pipeline-" + sourceType.toLowerCase(Locale.ROOT)
                    : pipeline.getName().trim();
            List<IngestionNodeStage> stages = pipeline.getStages() == null ? List.of() : List.copyOf(pipeline.getStages());
            result.add(new IngestionPipelineDefinition(name, sourceType, pipeline.isEnabled(), stages));
        }
        result.sort(Comparator.comparing(IngestionPipelineDefinition::name));
        return result;
    }

    private String normalizeSourceType(String sourceType) {
        if (sourceType == null || sourceType.isBlank()) {
            return "UNKNOWN";
        }
        return sourceType.trim().toUpperCase(Locale.ROOT);
    }
}
