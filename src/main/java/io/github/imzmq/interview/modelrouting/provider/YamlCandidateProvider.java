package io.github.imzmq.interview.modelrouting.provider;

import io.github.imzmq.interview.common.StringUtils;
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
                        StringUtils.trimToEmpty(candidate.getName()),
                        StringUtils.trimToEmpty(candidate.getProvider()),
                        StringUtils.trimToEmpty(candidate.getModel()),
                        StringUtils.trimToEmpty(candidate.getBeanName()),
                        candidate.getPriority(),
                        candidate.isSupportsThinking(),
                        "",
                        "",
                        "",
                        "YAML"
                ))
                .toList();
    }
}
