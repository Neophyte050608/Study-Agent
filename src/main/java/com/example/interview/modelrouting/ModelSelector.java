package com.example.interview.modelrouting;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 模型选择器。
 * 负责根据路由类型 (普通/深度推理) 从配置中过滤出可用的候选模型，并按照优先级和默认偏好进行排序。
 */
@Component
public class ModelSelector {

    private final ModelRoutingProperties properties;

    public ModelSelector(ModelRoutingProperties properties) {
        this.properties = properties;
    }

    /**
     * 根据路由类型选择并排序候选模型。
     * 排序规则：
     * 1. 优先级 (priority 越小越靠前)
     * 2. 是否为该类型的默认/偏好模型 (preferred 匹配的排前面)
     * 3. 名称字典序 (作为稳定排序的兜底)
     *
     * @param routeType 当前请求的路由类型 (GENERAL 或 THINKING)
     * @return 排序后的候选模型列表
     */
    public List<ModelRoutingCandidate> select(ModelRouteType routeType) {
        String preferred = routeType == ModelRouteType.THINKING ? properties.getDeepThinkingModel() : properties.getDefaultModel();
        return properties.getCandidates().stream()
                .filter(ModelRoutingProperties.Candidate::isEnabled)
                .filter(candidate -> routeType != ModelRouteType.THINKING || candidate.isSupportsThinking())
                .map(candidate -> new ModelRoutingCandidate(
                        normalizeName(candidate.getName()),
                        normalizeName(candidate.getProvider()),
                        normalizeName(candidate.getModel()),
                        normalizeName(candidate.getBeanName()),
                        candidate.getPriority(),
                        candidate.isSupportsThinking()
                ))
                .sorted(Comparator
                        .comparingInt(ModelRoutingCandidate::priority)
                        .thenComparingInt(candidate -> isPreferred(candidate, preferred) ? 0 : 1)
                        .thenComparing(ModelRoutingCandidate::name))
                .toList();
    }

    private boolean isPreferred(ModelRoutingCandidate candidate, String preferred) {
        if (preferred == null || preferred.isBlank()) {
            return false;
        }
        String normalizedPreferred = preferred.trim().toLowerCase(Locale.ROOT);
        return normalizedPreferred.equals(candidate.name().toLowerCase(Locale.ROOT))
                || normalizedPreferred.equals(candidate.model().toLowerCase(Locale.ROOT));
    }

    private String normalizeName(String value) {
        return value == null ? "" : value.trim();
    }
}
