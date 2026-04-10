package com.example.interview.modelrouting;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Primary
@Component
public class CompositeCandidateProvider implements CandidateProvider {

    private final YamlCandidateProvider yamlCandidateProvider;
    private final DatabaseCandidateProvider databaseCandidateProvider;

    public CompositeCandidateProvider(YamlCandidateProvider yamlCandidateProvider,
                                      DatabaseCandidateProvider databaseCandidateProvider) {
        this.yamlCandidateProvider = yamlCandidateProvider;
        this.databaseCandidateProvider = databaseCandidateProvider;
    }

    @Override
    public List<ModelRoutingCandidate> getCandidates() {
        Map<String, ModelRoutingCandidate> merged = new LinkedHashMap<>();
        for (ModelRoutingCandidate yamlCandidate : yamlCandidateProvider.getCandidates()) {
            merged.put(yamlCandidate.name(), yamlCandidate);
        }
        for (ModelRoutingCandidate databaseCandidate : databaseCandidateProvider.getCandidates()) {
            merged.put(databaseCandidate.name(), databaseCandidate);
        }
        return List.copyOf(merged.values());
    }
}
