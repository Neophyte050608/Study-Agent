package io.github.imzmq.interview.agent.application.contract.execution.dynamic;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record DynamicCapabilityDescriptor(
        String capabilityId,
        int capabilityVersion,
        DynamicEffectType effectType,
        Map<String, DynamicArgumentDefinition> arguments,
        Set<String> allowedDependencyCapabilities,
        int estimatedCost,
        boolean requireSuccessfulDependencies) {
    public DynamicCapabilityDescriptor {
        if (capabilityId == null || capabilityId.isBlank()) {
            throw new IllegalArgumentException("Capability ID is required");
        }
        if (capabilityVersion <= 0) {
            throw new IllegalArgumentException("Capability version must be positive");
        }
        Objects.requireNonNull(effectType, "Effect type is required");
        if (estimatedCost <= 0) {
            throw new IllegalArgumentException("Estimated cost must be positive");
        }

        LinkedHashMap<String, DynamicArgumentDefinition> argumentCopy = new LinkedHashMap<>();
        if (arguments != null) {
            arguments.forEach((name, definition) -> {
                if (name == null || definition == null || !name.equals(definition.name())) {
                    throw new IllegalArgumentException("Argument definitions must have matching unique names");
                }
                if (argumentCopy.putIfAbsent(name, definition) != null) {
                    throw new IllegalArgumentException("Argument definitions must have unique names");
                }
            });
        }
        arguments = Collections.unmodifiableMap(argumentCopy);
        allowedDependencyCapabilities = allowedDependencyCapabilities == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(allowedDependencyCapabilities));
    }
}
