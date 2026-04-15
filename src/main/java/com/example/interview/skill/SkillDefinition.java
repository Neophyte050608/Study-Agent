package com.example.interview.skill;

import java.util.List;

public record SkillDefinition(
        String id,
        String description,
        SkillExecutionMode executionMode,
        List<String> allowedMcpCapabilities,
        SkillFailurePolicy failurePolicy
) {
}
