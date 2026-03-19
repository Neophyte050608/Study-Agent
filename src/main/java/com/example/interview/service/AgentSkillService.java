package com.example.interview.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class AgentSkillService {

    private static final Logger logger = LoggerFactory.getLogger(AgentSkillService.class);
    private static final Pattern NAME_PATTERN = Pattern.compile("(?m)^name:\\s*\"([^\"]+)\"\\s*$");
    private static final Pattern DESCRIPTION_PATTERN = Pattern.compile("(?m)^description:\\s*\"([^\"]+)\"\\s*$");
    private static final int MAX_SKILL_DESC_LENGTH = 180;

    private final Map<String, SkillDefinition> skillDefinitions;

    public AgentSkillService(@Value("${app.agent.skills.paths:skills,.trae/skills}") String rawPaths) {
        this.skillDefinitions = loadSkillDefinitions(rawPaths);
    }

    public String globalInstruction() {
        if (skillDefinitions.isEmpty()) {
            return "";
        }
        String summary = skillDefinitions.values().stream()
                .map(skill -> "- " + skill.name() + "： " + skill.description())
                .collect(Collectors.joining("\n"));
        return "以下是项目内可用技能，请按任务匹配优先遵循对应 Skill 的 Purpose、Workflow、Guardrails：\n" + summary;
    }

    public String resolveSkillBlock(String... names) {
        if (skillDefinitions.isEmpty() || names == null || names.length == 0) {
            return "";
        }
        List<String> details = new ArrayList<>();
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            SkillDefinition skill = skillDefinitions.get(name.trim().toLowerCase(Locale.ROOT));
            if (skill != null) {
                details.add("- Skill: " + skill.name() + "；规则：" + compactText(skill.description(), MAX_SKILL_DESC_LENGTH));
            }
        }
        if (details.isEmpty()) {
            return "";
        }
        return "请遵循以下技能约束：\n" + String.join("\n", details);
    }

    private Map<String, SkillDefinition> loadSkillDefinitions(String rawPaths) {
        LinkedHashMap<String, SkillDefinition> result = new LinkedHashMap<>();
        if (rawPaths == null || rawPaths.isBlank()) {
            return result;
        }
        String[] pathItems = rawPaths.split(",");
        for (String item : pathItems) {
            String candidate = item == null ? "" : item.trim();
            if (candidate.isBlank()) {
                continue;
            }
            Path root = Paths.get(candidate);
            if (!Files.exists(root) || !Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.list(root)) {
                List<Path> skillDirs = stream.filter(Files::isDirectory).toList();
                for (Path skillDir : skillDirs) {
                    Path skillFile = skillDir.resolve("SKILL.md");
                    if (!Files.exists(skillFile) || !Files.isRegularFile(skillFile)) {
                        continue;
                    }
                    String content = Files.readString(skillFile, StandardCharsets.UTF_8);
                    SkillDefinition definition = parseSkillDefinition(skillDir.getFileName().toString(), content);
                    result.putIfAbsent(definition.key(), definition);
                }
            } catch (IOException e) {
                logger.warn("读取技能目录失败: {}, 原因: {}", root, e.getMessage());
            }
        }
        return result;
    }

    private SkillDefinition parseSkillDefinition(String fallbackName, String content) {
        String name = matchOrDefault(NAME_PATTERN, content, fallbackName);
        String description = matchOrDefault(DESCRIPTION_PATTERN, content, "用于面试流程协作");
        String detail = stripFrontMatter(content);
        return new SkillDefinition(name, description, detail);
    }

    private String matchOrDefault(Pattern pattern, String content, String fallback) {
        if (content == null || content.isBlank()) {
            return fallback;
        }
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            String value = matcher.group(1);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return fallback;
    }

    private String stripFrontMatter(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content.replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) {
            return normalized.trim();
        }
        int second = normalized.indexOf("\n---\n", 4);
        if (second < 0) {
            return normalized.trim();
        }
        return normalized.substring(second + 5).trim();
    }

    private String compactText(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    private record SkillDefinition(String name, String description, String detail) {
        private String key() {
            return name.trim().toLowerCase(Locale.ROOT);
        }
    }
}
