package com.example.interview.skill;

public interface ExecutableSkill {

    SkillDefinition definition();

    SkillExecutionResult execute(SkillExecutionContext context);
}
