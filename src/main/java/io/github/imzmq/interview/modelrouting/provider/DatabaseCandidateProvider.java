package io.github.imzmq.interview.modelrouting.provider;

import io.github.imzmq.interview.common.StringUtils;
import io.github.imzmq.interview.entity.modelrouting.ModelCandidateDO;
import io.github.imzmq.interview.modelrouting.catalog.ModelCandidateService;
import io.github.imzmq.interview.modelrouting.core.ModelRoutingCandidate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class DatabaseCandidateProvider implements CandidateProvider {

    private final ModelCandidateService modelCandidateService;

    public DatabaseCandidateProvider(ModelCandidateService modelCandidateService) {
        this.modelCandidateService = modelCandidateService;
    }

    @Override
    public List<ModelRoutingCandidate> getCandidates() {
        return modelCandidateService.listEnabled().stream()
                .filter(this::matchesRouteType)
                .map(ModelRoutingCandidate::from)
                .toList();
    }

    private boolean matchesRouteType(ModelCandidateDO candidate) {
        String routeType = StringUtils.trimToEmpty(candidate.getRouteType());
        if (routeType.isBlank()) {
            return true;
        }
        String normalized = routeType.toUpperCase(Locale.ROOT);
        return "GENERAL".equals(normalized)
                || "THINKING".equals(normalized)
                || "RETRIEVAL".equals(normalized)
                || "ALL".equals(normalized);
    }

}






