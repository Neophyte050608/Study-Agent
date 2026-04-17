package io.github.imzmq.interview.modelrouting.execution;

import io.github.imzmq.interview.modelrouting.core.ModelRouteType;
import io.github.imzmq.interview.modelrouting.core.ModelRoutingCandidate;
import io.github.imzmq.interview.modelrouting.core.ModelRoutingProperties;
import io.github.imzmq.interview.modelrouting.provider.CandidateProvider;
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
    private final CandidateProvider candidateProvider;

    public ModelSelector(ModelRoutingProperties properties, CandidateProvider candidateProvider) {
        this.properties = properties;
        this.candidateProvider = candidateProvider;
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
        return select(routeType, preferredFor(routeType, null));
    }

    public List<ModelRoutingCandidate> select(ModelRouteType routeType, String preferredCandidateName) {
        String preferred = preferredFor(routeType, preferredCandidateName);
        return candidateProvider.getCandidates().stream()
                .filter(candidate -> supportsRouteType(candidate, routeType))
                .filter(candidate -> routeType != ModelRouteType.THINKING || candidate.supportsThinking())
                .sorted(Comparator
                        .comparingInt((ModelRoutingCandidate candidate) -> isPreferred(candidate, preferred) ? 0 : 1)
                        .thenComparingInt(ModelRoutingCandidate::priority)
                        .thenComparing(ModelRoutingCandidate::name))
                .toList();
    }

    private String preferredFor(ModelRouteType routeType, String preferredCandidateName) {
        if (preferredCandidateName != null && !preferredCandidateName.isBlank()) {
            return preferredCandidateName;
        }
        if (routeType == ModelRouteType.THINKING) {
            return properties.getDeepThinkingModel();
        }
        if (routeType == ModelRouteType.RETRIEVAL) {
            return properties.getRetrievalModel();
        }
        return properties.getDefaultModel();
    }

    private boolean supportsRouteType(ModelRoutingCandidate candidate, ModelRouteType routeType) {
        String normalizedRouteType = normalizeRouteType(candidate.routeType());
        if (normalizedRouteType.isBlank() || "ALL".equals(normalizedRouteType)) {
            return true;
        }
        return normalizedRouteType.equals(routeType.name());
    }

    private boolean isPreferred(ModelRoutingCandidate candidate, String preferred) {
        if (preferred == null || preferred.isBlank()) {
            return false;
        }
        String normalizedPreferred = preferred.trim().toLowerCase(Locale.ROOT);
        return normalizedPreferred.equals(candidate.name().toLowerCase(Locale.ROOT))
                || normalizedPreferred.equals(candidate.model().toLowerCase(Locale.ROOT));
    }

    private String normalizeRouteType(String routeType) {
        return routeType == null ? "" : routeType.trim().toUpperCase(Locale.ROOT);
    }

}



