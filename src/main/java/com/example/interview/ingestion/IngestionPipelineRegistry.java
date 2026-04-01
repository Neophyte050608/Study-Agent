package com.example.interview.ingestion;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 入库管道注册表（从配置解析为运行时定义）。
 *
 * <p>该类负责将 {@link IngestionPipelineProperties} 解析为不可变的 {@link IngestionPipelineDefinition}，
 * 并按数据源类型（sourceType）提供“必选管道”的解析能力。</p>
 */
@Service
public class IngestionPipelineRegistry {

    private final IngestionPipelineProperties properties;

    public IngestionPipelineRegistry(IngestionPipelineProperties properties) {
        this.properties = properties;
    }

    /**
     * 根据数据源类型解析一个“必存在”的管道定义。
     *
     * <p>若找不到匹配管道，会抛出异常以提示配置缺失或 sourceType 传入错误。</p>
     *
     * @param sourceType 数据源类型（允许大小写/空白差异）
     * @return 匹配的数据源管道定义
     */
    public IngestionPipelineDefinition resolveRequiredBySource(String sourceType) {
        String normalized = normalizeSourceType(sourceType);
        return listPipelines().stream()
                .filter(item -> item.sourceType().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未找到匹配的数据源管道: " + normalized));
    }

    /**
     * 列出当前所有管道定义（已做规范化与默认值补齐）。
     *
     * <p>返回结果按管道名称排序，方便 UI 展示。</p>
     *
     * @return 管道定义列表
     */
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
