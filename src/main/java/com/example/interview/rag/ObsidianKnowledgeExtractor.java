package com.example.interview.rag;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ObsidianKnowledgeExtractor {

    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```([\\s\\S]*?)```");
    private static final Pattern TAG_PATTERN = Pattern.compile("(^|\\s)#([\\p{L}\\p{N}_-]{2,})");
    private static final Pattern HEADING_PATTERN = Pattern.compile("^#{1,3}\\s+(.*)$");
    private static final Pattern SUMMARY_PATTERN = Pattern.compile("(?i)^(总结|结论|TL;DR|复盘|面试总结)[:：\\s-]*(.*)$");
    private static final Pattern KEYWORD_LINE_PATTERN = Pattern.compile("(?i)^(关键词|关键字|tags?)[:：\\s-]*(.*)$");

    private static final List<String> DIARY_HINTS = List.of("日记", "daily", "journal", "碎碎念", "随笔", "todo");
    private static final List<String> TECH_STACK_HINTS = List.of("java", "spring", "spring boot", "mysql", "redis", "kafka", "mq", "jvm", "docker", "kubernetes", "nginx", "rpc", "netty");
    private static final List<String> PROJECT_HINTS = List.of("项目", "模块", "需求", "架构", "服务", "上线", "接口", "联调", "落地");
    private static final List<String> BAGUA_HINTS = List.of("八股", "面试", "原理", "为什么", "区别", "如何", "源码", "机制");
    private static final List<String> ALGORITHM_HINTS = List.of("算法", "复杂度", "链表", "二叉树", "动态规划", "回溯", "排序", "二分", "滑动窗口", "leetcode");
    private static final List<String> PROJECT_DIFFICULTY_HINTS = List.of("难点", "挑战", "线上", "故障", "排查", "瓶颈", "优化", "降级", "事故", "监控");
    private static final List<String> INTERVIEW_SOURCE_HINTS = List.of("面经", "interview-experience", "interview_experience", "interview", "题库", "真题");

    public ExtractionResult extract(String markdown, String filePath) {
        if (markdown == null || markdown.isBlank()) {
            return ExtractionResult.empty();
        }

        String normalizedPath = filePath == null ? "" : filePath.toLowerCase(Locale.ROOT).replace("\\", "/");
        String normalizedText = markdown.toLowerCase(Locale.ROOT);
        boolean possibleDiary = DIARY_HINTS.stream().anyMatch(normalizedPath::contains);
        int technicalSignal = countSignals(normalizedText, TECH_STACK_HINTS) + countSignals(normalizedText, PROJECT_HINTS)
                + countSignals(normalizedText, BAGUA_HINTS) + countSignals(normalizedText, ALGORITHM_HINTS);
        if (possibleDiary && technicalSignal < 2) {
            return ExtractionResult.empty();
        }

        String title = extractTitle(markdown, filePath);
        Set<String> keywords = extractKeywords(markdown);
        List<String> summaries = extractSummaries(markdown);
        List<String> techBullets = extractTechnicalBullets(markdown);
        List<String> codeBlocks = extractCodeBlocks(markdown);
        Set<String> knowledgeTags = detectKnowledgeTags(normalizedText);
        String sourceType = detectSourceType(normalizedPath, normalizedText, knowledgeTags);

        StringBuilder builder = new StringBuilder();
        builder.append("标题：").append(title).append("\n");
        if (!knowledgeTags.isEmpty()) {
            builder.append("知识类型：").append(String.join("、", knowledgeTags)).append("\n");
        }
        if (!keywords.isEmpty()) {
            builder.append("关键词：").append(String.join("、", keywords)).append("\n");
        }
        if (!summaries.isEmpty()) {
            builder.append("总结：\n").append(String.join("\n", summaries)).append("\n");
        }
        if (!techBullets.isEmpty()) {
            builder.append("技术要点：\n").append(String.join("\n", techBullets)).append("\n");
        }
        if (!codeBlocks.isEmpty()) {
            builder.append("代码片段：\n").append(String.join("\n\n", codeBlocks.subList(0, Math.min(2, codeBlocks.size())))).append("\n");
        }

        String extracted = builder.toString().trim();
        if (extracted.length() < 40) {
            return ExtractionResult.empty();
        }

        Document document = new Document(extracted);
        document.getMetadata().put("file_path", filePath);
        document.getMetadata().put("source_type", sourceType);
        document.getMetadata().put("knowledge_tags", String.join(",", knowledgeTags));
        return new ExtractionResult(List.of(document));
    }

    private String extractTitle(String markdown, String filePath) {
        String[] lines = markdown.split("\\R");
        for (String line : lines) {
            Matcher matcher = HEADING_PATTERN.matcher(line.trim());
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        }
        if (filePath == null || filePath.isBlank()) {
            return "未命名笔记";
        }
        try {
            String fileName = Path.of(filePath).getFileName().toString();
            int dot = fileName.lastIndexOf('.');
            return dot > 0 ? fileName.substring(0, dot) : fileName;
        } catch (Exception ignored) {
            return filePath;
        }
    }

    private Set<String> extractKeywords(String markdown) {
        Set<String> keywords = new LinkedHashSet<>();
        Matcher tagMatcher = TAG_PATTERN.matcher(markdown);
        while (tagMatcher.find()) {
            keywords.add(tagMatcher.group(2));
        }
        String[] lines = markdown.split("\\R");
        for (String line : lines) {
            Matcher keywordLine = KEYWORD_LINE_PATTERN.matcher(line.trim());
            if (keywordLine.find()) {
                String raw = keywordLine.group(2);
                String[] parts = raw.split("[,，、/|]");
                for (String part : parts) {
                    String item = part.trim();
                    if (!item.isBlank()) {
                        keywords.add(item);
                    }
                }
            }
        }
        return keywords;
    }

    private List<String> extractSummaries(String markdown) {
        List<String> result = new ArrayList<>();
        String[] lines = markdown.split("\\R");
        for (String line : lines) {
            Matcher matcher = SUMMARY_PATTERN.matcher(line.trim());
            if (matcher.find()) {
                String content = matcher.group(2).trim();
                if (!content.isBlank()) {
                    result.add("- " + content);
                }
            }
        }
        return result;
    }

    private List<String> extractTechnicalBullets(String markdown) {
        List<String> bullets = new ArrayList<>();
        String[] lines = markdown.split("\\R");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.length() < 8 || line.length() > 220) {
                continue;
            }
            if (!line.startsWith("-") && !line.startsWith("*") && !line.matches("^\\d+\\..*")) {
                continue;
            }
            String normalized = line.toLowerCase(Locale.ROOT);
            int signal = countSignals(normalized, TECH_STACK_HINTS) + countSignals(normalized, PROJECT_HINTS)
                    + countSignals(normalized, BAGUA_HINTS) + countSignals(normalized, ALGORITHM_HINTS)
                    + countSignals(normalized, PROJECT_DIFFICULTY_HINTS);
            if (signal > 0) {
                bullets.add(line);
            }
        }
        return bullets;
    }

    private List<String> extractCodeBlocks(String markdown) {
        List<String> blocks = new ArrayList<>();
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(markdown);
        while (matcher.find()) {
            String block = matcher.group(1).trim();
            if (!block.isBlank()) {
                blocks.add(block.length() > 700 ? block.substring(0, 700) : block);
            }
        }
        return blocks;
    }

    private Set<String> detectKnowledgeTags(String normalizedText) {
        Set<String> tags = new LinkedHashSet<>();
        if (countSignals(normalizedText, TECH_STACK_HINTS) > 0) {
            tags.add("技术栈");
        }
        if (countSignals(normalizedText, PROJECT_HINTS) > 0) {
            tags.add("项目");
        }
        if (countSignals(normalizedText, BAGUA_HINTS) > 0) {
            tags.add("八股");
        }
        if (countSignals(normalizedText, ALGORITHM_HINTS) > 0) {
            tags.add("算法");
        }
        if (countSignals(normalizedText, PROJECT_DIFFICULTY_HINTS) > 0) {
            tags.add("项目难点");
        }
        return tags;
    }

    private int countSignals(String text, List<String> hints) {
        int count = 0;
        for (String hint : hints) {
            if (text.contains(hint.toLowerCase(Locale.ROOT))) {
                count++;
            }
        }
        return count;
    }

    private String detectSourceType(String normalizedPath, String normalizedText, Set<String> knowledgeTags) {
        boolean pathHint = INTERVIEW_SOURCE_HINTS.stream().anyMatch(normalizedPath::contains);
        boolean textHint = INTERVIEW_SOURCE_HINTS.stream().anyMatch(normalizedText::contains);
        boolean baguaTag = knowledgeTags != null && knowledgeTags.contains("八股");
        if (pathHint || (textHint && baguaTag)) {
            return "interview_experience";
        }
        return "obsidian";
    }

    public record ExtractionResult(List<Document> documents) {
        public static ExtractionResult empty() {
            return new ExtractionResult(List.of());
        }

        public boolean isEmpty() {
            return documents == null || documents.isEmpty();
        }
    }
}
