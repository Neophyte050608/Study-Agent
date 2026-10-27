package io.github.imzmq.interview.agent.application.contract.execution;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record DagExecutionResult(String code, Map<String, DagNodeResult> nodes) {
    public DagExecutionResult { nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes)); }
}
