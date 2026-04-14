package com.example.interview.modelrouting;

import com.example.interview.entity.ModelCandidateDO;
import com.example.interview.service.model.ModelCandidateService;
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
                .map(candidate -> new ModelRoutingCandidate(
                        normalize(candidate.getName()),
                        normalize(candidate.getProvider()),
                        normalize(candidate.getModel()),
                        "",
                        candidate.getPriority() == null ? 100 : candidate.getPriority(),
                        Boolean.TRUE.equals(candidate.getSupportsThinking()),
                        normalize(candidate.getBaseUrl()),
                        normalize(candidate.getApiKeyEncrypted()),
                        normalize(candidate.getRouteType()),
                        "DATABASE"
                ))
                .toList();
    }

    private boolean matchesRouteType(ModelCandidateDO candidate) {
        String routeType = normalize(candidate.getRouteType());
        if (routeType.isBlank()) {
            return true;
        }
        String normalized = routeType.toUpperCase(Locale.ROOT);
        return "GENERAL".equals(normalized)
                || "THINKING".equals(normalized)
                || "RETRIEVAL".equals(normalized)
                || "ALL".equals(normalized);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
