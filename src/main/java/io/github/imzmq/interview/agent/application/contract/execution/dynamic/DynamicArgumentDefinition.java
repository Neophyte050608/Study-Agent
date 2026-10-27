package io.github.imzmq.interview.agent.application.contract.execution.dynamic;

import java.util.Objects;

public record DynamicArgumentDefinition(
        String name,
        DynamicArgumentType type,
        boolean required,
        Object defaultValue,
        Long minimum,
        Long maximum) {
    public DynamicArgumentDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Argument name is required");
        }
        Objects.requireNonNull(type, "Argument type is required");
        if (minimum != null && maximum != null && minimum > maximum) {
            throw new IllegalArgumentException("Argument minimum must not exceed maximum");
        }
    }
}
