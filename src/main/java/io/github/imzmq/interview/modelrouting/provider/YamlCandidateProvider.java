package io.github.imzmq.interview.modelrouting.provider;

import io.github.imzmq.interview.modelrouting.core.ModelRoutingCandidate;
import io.github.imzmq.interview.modelrouting.core.ModelRoutingProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class YamlCandidateProvider implements CandidateProvider {

    private final ModelRoutingProperties properties;

    public YamlCandidateProvider(ModelRoutingProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<ModelRoutingCandidate> getCandidates() {
        return properties.getCandidates().stream()
                .filter(ModelRoutingProperties.Candidate::isEnabled)
                .map(candidate -> new ModelRoutingCandidate(
                        normalize(candidate.getName()),
                        normalize(candidate.getProvider()),
                        normalize(candidate.getModel()),
                        normalize(candidate.getBeanName()),
                        candidate.getPriority(),
                        candidate.isSupportsThinking(),
                        "",
                        "",
                        "",
                        "YAML"
                ))
                .toList();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}



