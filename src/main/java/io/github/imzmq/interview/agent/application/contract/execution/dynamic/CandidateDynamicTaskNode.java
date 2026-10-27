package io.github.imzmq.interview.agent.application.contract.execution.dynamic;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record CandidateDynamicTaskNode(
        String id,
        String capability,
        List<String> dependsOn,
        Map<String, Object> arguments) {
    public CandidateDynamicTaskNode {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        arguments = arguments == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }
}
