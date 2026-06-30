package io.github.imzmq.interview.agent.application.context;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class AgentContextSourceRegistry {

    private final List<AgentContextSource> sources;

    public AgentContextSourceRegistry(List<AgentContextSource> sources) {
        this.sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public List<AgentContextSource> sourcesFor(AgentContextSlotKind kind) {
        if (kind == null) {
            return List.of();
        }
        List<AgentContextSource> matched = new ArrayList<>();
        for (AgentContextSource source : sources) {
            if (source.supports(kind)) {
                matched.add(source);
            }
        }
        return matched;
    }
}
