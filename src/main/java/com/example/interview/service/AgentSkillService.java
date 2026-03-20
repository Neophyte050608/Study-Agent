package com.example.interview.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Agent 技能管理服务。
 * 
 * 该服务负责管理项目内定义的各类 Agent 技能（Skill）。
 * 技能通常以目录形式存在，内部包含 SKILL.md 文件定义其 Purpose、Workflow 和 Guardrails。
 * 
 * 核心功能：
 * 1. 自动扫描：从配置的路径中扫描并解析 SKILL.md。
 * 2. 注入指令：为 LLM 生成全局技能摘要或特定技能的详细约束块。
 * 3. 实时刷新：支持感知技能文件的物理变化（通过缓存与最后修改时间校验）。
 */
@Service
public class AgentSkillService {

    private static final Logger logger = LoggerFactory.getLogger(AgentSkillService.class);
    private static final Pattern NAME_PATTERN = Pattern.compile("^name:\\s*\"([^\"]+)\"\\s*$");
    private static final Pattern DESCRIPTION_PATTERN = Pattern.compile("^description:\\s*\"([^\"]+)\"\\s*$");
    private static final int MAX_SKILL_DESC_LENGTH = 180;

    /** 存储技能元数据，Key 为技能名称（归一化后）或目录名 */
    private final Map<String, SkillMetadata> skillMetadatas;
    /** 技能全文内容缓存，避免频繁 IO */
    private final Map<String, SkillSnapshot> detailCache = new ConcurrentHashMap<>();

    public AgentSkillService(@Value("${app.agent.skills.paths:skills,.trae/skills}") String rawPaths) {
        this.skillMetadatas = loadSkillMetadatas(rawPaths);
    }

    /**
     * 生成全局技能指令摘要。
     * 用于系统提示词，让 LLM 了解当前环境有哪些可用技能。
     */
    public String globalInstruction() {
        if (skillMetadatas.isEmpty()) {
            return "";
        }
        Set<String> visited = new HashSet<>();
        String summary = skillMetadatas.values().stream()
                .filter(skill -> visited.add(skill.directoryKey()))
                .map(skill -> "- " + skill.name() + "： " + compactText(skill.description(), MAX_SKILL_DESC_LENGTH))
                .collect(Collectors.joining("\n"));
        return "以下是项目内可用技能，请按任务匹配优先遵循对应 Skill 的 Purpose、Workflow、Guardrails：\n" + summary;
    }

    /**
     * 为选定的技能生成详细的约束块。
     * 
     * @param names 技能名称列表（支持目录名或 SKILL.md 中定义的 name）
     * @return 包含技能全文（已剥离 FrontMatter）的指令文本，用于增强 Prompt 的约束力
     */
    public String resolveSkillBlock(String... names) {
        if (skillMetadatas.isEmpty() || names == null || names.length == 0) {
            return "";
        }
        List<String> details = new ArrayList<>();
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            SkillMetadata metadata = skillMetadatas.get(normalizeKey(name));
            if (metadata != null) {
                String detail = loadSkillDetail(metadata);
                if (detail.isBlank()) {
                    details.add("- Skill: " + metadata.name() + "\n" + metadata.description());
                } else {
                    details.add("- Skill: " + metadata.name() + "\n" + detail);
                }
            }
        }
        if (details.isEmpty()) {
            return "";
        }
        return "请遵循以下技能约束：\n" + String.join("\n\n", details);
    }

    /**
     * 列出所有技能的简要信息。
     */
    public List<SkillSummary> listSkillSummaries() {
        if (skillMetadatas.isEmpty()) {
            return List.of();
        }
        Set<String> visited = new HashSet<>();
        return skillMetadatas.values().stream()
                .filter(skill -> visited.add(skill.directoryKey()))
                .map(skill -> new SkillSummary(skill.name(), skill.description()))
                .toList();
    }

    /**
     * 扫描目录加载技能元数据。
     */
    private Map<String, SkillMetadata> loadSkillMetadatas(String rawPaths) {
        LinkedHashMap<String, SkillMetadata> result = new LinkedHashMap<>();
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
                    SkillMetadata metadata = parseSkillMetadata(skillDir, skillFile);
                    // 建立双重索引：目录名和 Skill 定义中的 name
                    result.putIfAbsent(normalizeKey(skillDir.getFileName().toString()), metadata);
                    result.putIfAbsent(normalizeKey(metadata.name()), metadata);
                }
            } catch (IOException e) {
                logger.warn("读取技能目录失败: {}, 原因: {}", root, e.getMessage());
            }
        }
        return result;
    }

    /**
     * 解析 SKILL.md 中的 FrontMatter（YAML 头部）。
     */
    private SkillMetadata parseSkillMetadata(Path skillDir, Path skillFile) throws IOException {
        String fallbackName = skillDir.getFileName().toString();
        String name = "";
        String description = "";
        int delimiterCount = 0;
        try (BufferedReader reader = Files.newBufferedReader(skillFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if ("---".equals(trimmed)) {
                    delimiterCount++;
                    if (delimiterCount >= 2) {
                        break;
                    }
                    continue;
                }
                if (delimiterCount != 1) {
                    continue;
                }
                Matcher nameMatcher = NAME_PATTERN.matcher(trimmed);
                if (nameMatcher.find()) {
                    name = nameMatcher.group(1) == null ? "" : nameMatcher.group(1).trim();
                }
                Matcher descriptionMatcher = DESCRIPTION_PATTERN.matcher(trimmed);
                if (descriptionMatcher.find()) {
                    description = descriptionMatcher.group(1) == null ? "" : descriptionMatcher.group(1).trim();
                }
            }
        }
        if (name.isBlank()) {
            logger.warn("技能文件缺少 name 字段，将使用目录名兜底: {}", skillFile);
            name = fallbackName;
        }
        if (description.isBlank()) {
            logger.warn("技能文件缺少 description 字段，将使用默认描述兜底: {}", skillFile);
            description = "用于面试流程协作";
        }
        return new SkillMetadata(name, description, skillFile, normalizeKey(fallbackName));
    }

    /**
     * 加载技能全文并缓存。
     */
    private String loadSkillDetail(SkillMetadata metadata) {
        if (metadata == null || metadata.filePath() == null) {
            return "";
        }
        try {
            long lastModified = Files.getLastModifiedTime(metadata.filePath()).toMillis();
            SkillSnapshot snapshot = detailCache.get(metadata.directoryKey());
            if (snapshot != null && snapshot.lastModified() == lastModified) {
                return snapshot.detail();
            }
            String content = Files.readString(metadata.filePath(), StandardCharsets.UTF_8);
            String detail = stripFrontMatter(content);
            detailCache.put(metadata.directoryKey(), new SkillSnapshot(detail, lastModified));
            return detail;
        } catch (IOException e) {
            logger.warn("读取技能全文失败: {}, 原因: {}", metadata.filePath(), e.getMessage());
            return "";
        }
    }

    /** 去除 Markdown 头部 YAML */
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

    /** 压缩描述文本长度 */
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

    private String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** 技能内部元数据 */
    private record SkillMetadata(
            String name,
            String description,
            Path filePath,
            String directoryKey
    ) {
    }

    /** 技能内容快照（用于缓存） */
    private record SkillSnapshot(
            String detail,
            long lastModified
    ) {
    }

    /** 对外暴露的技能摘要 */
    public record SkillSummary(
            String name,
            String description
    ) {
    }
}
