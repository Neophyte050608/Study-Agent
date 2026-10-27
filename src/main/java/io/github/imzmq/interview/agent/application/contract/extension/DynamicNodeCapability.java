package io.github.imzmq.interview.agent.application.contract.extension;

import io.github.imzmq.interview.agent.application.contract.execution.DagNodeResult;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicCapabilityDescriptor;
import io.github.imzmq.interview.agent.application.contract.execution.dynamic.DynamicExecutionContext;

import java.util.Map;

public interface DynamicNodeCapability<A> {
    DynamicCapabilityDescriptor descriptor();

    A decodeArguments(Map<String, Object> normalizedArguments);

    Object execute(
            DynamicExecutionContext context,
            Map<String, DagNodeResult> upstream,
            A arguments);
}
