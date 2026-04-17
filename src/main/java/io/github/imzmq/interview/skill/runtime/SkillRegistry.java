package io.github.imzmq.interview.skill.runtime;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import io.github.imzmq.interview.skill.core.ExecutableSkill;

@Service
public class SkillRegistry {

    private final Map<String, ExecutableSkill> skillsById;

    public SkillRegistry(List<ExecutableSkill> skills) {
        LinkedHashMap<String, ExecutableSkill> map = new LinkedHashMap<>();
        if (skills != null) {
            for (ExecutableSkill skill : skills) {
                if (skill == null || skill.definition() == null || skill.definition().id() == null) {
                    continue;
                }
                map.putIfAbsent(normalize(skill.definition().id()), skill);
            }
        }
        this.skillsById = Map.copyOf(map);
    }

    public Optional<ExecutableSkill> get(String skillId) {
        return Optional.ofNullable(skillsById.get(normalize(skillId)));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}

