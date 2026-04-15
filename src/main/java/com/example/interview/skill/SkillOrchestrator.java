package com.example.interview.skill;

import com.example.interview.config.SkillExecutionProperties;
import org.springframework.stereotype.Service;

@Service
public class SkillOrchestrator {

    private final SkillRegistry skillRegistry;
    private final SkillExecutor skillExecutor;
    private final SkillExecutionProperties properties;

    public SkillOrchestrator(SkillRegistry skillRegistry,
                             SkillExecutor skillExecutor,
                             SkillExecutionProperties properties) {
        this.skillRegistry = skillRegistry;
        this.skillExecutor = skillExecutor;
        this.properties = properties;
    }

    public SkillExecutionBudget newBudget() {
        return new SkillExecutionBudget(
                properties.getMaxSkillExecutions(),
                properties.getMaxMcpCalls(),
                properties.getExtraLatencyBudgetMs()
        );
    }

    public SkillExecutionResult execute(String skillId, SkillExecutionContext context) {
        return skillRegistry.get(skillId)
                .map(skill -> skillExecutor.execute(skill, context))
                .orElseGet(() -> SkillExecutionResult.skipped(skillId, "skill_not_registered"));
    }

    public SkillDefinition definition(String skillId) {
        return skillRegistry.get(skillId)
                .map(ExecutableSkill::definition)
                .orElse(null);
    }
}
