package io.github.imzmq.interview.tools.skill.core;

import java.util.List;
import io.github.imzmq.interview.tools.skill.policy.SkillFailurePolicy;

public record SkillDefinition(
        String id,
        String description,
        SkillExecutionMode executionMode,
        List<String> allowedMcpCapabilities,
        SkillFailurePolicy failurePolicy
) {
}
