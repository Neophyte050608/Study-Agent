package com.example.interview.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将完整的 Markdown 文本基于标题层级拆分为多个 Section。
 */
public class MarkdownSectionBuilder {

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.*)$");

    /**
     * 将 Markdown 文本拆分为结构化的 Sections 列表。
     * 
     * @param markdown 原始 Markdown 文本
     * @param maxHeadingLevels 最大保留的标题层级 (如 3 表示只识别 #, ##, ### 作为边界)
     * @return 拆分后的 Section 列表
     */
    public static List<MarkdownSection> buildSections(String markdown, int maxHeadingLevels) {
        List<MarkdownSection> sections = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return sections;
        }

        String[] lines = markdown.split("\\R");
        
        // 跟踪当前的标题层级路径
        List<String> currentPath = new ArrayList<>();
        MarkdownSection currentSection = new MarkdownSection(0, "Root", new ArrayList<>());
        
        boolean inCodeBlock = false;

        for (String line : lines) {
            String trimmed = line.trim();

            // 处理代码块状态，代码块内的 `#` 不应被视为标题
            if (trimmed.startsWith("```")) {
                inCodeBlock = !inCodeBlock;
                currentSection.appendLine(line);
                continue;
            }

            if (!inCodeBlock) {
                Matcher matcher = HEADING_PATTERN.matcher(line);
                if (matcher.find()) {
                    int level = matcher.group(1).length();
                    String heading = matcher.group(2).trim();

                    // 仅当层级在允许范围内时才作为切分边界
                    if (level <= maxHeadingLevels) {
                        // 保存上一个 section (如果不为空)
                        if (!currentSection.getContent().isBlank()) {
                            sections.add(currentSection);
                        }

                        // 更新路径状态
                        // 如果当前层级小于等于现有路径深度，说明在退栈
                        while (currentPath.size() >= level) {
                            currentPath.remove(currentPath.size() - 1);
                        }
                        currentPath.add(heading);

                        // 开启新的 section
                        currentSection = new MarkdownSection(level, heading, new ArrayList<>(currentPath));
                        continue;
                    }
                }
            }

            // 非标题行或代码块行，直接追加到当前 section
            currentSection.appendLine(line);
        }

        // 保存最后一个 section
        if (!currentSection.getContent().isBlank()) {
            sections.add(currentSection);
        }

        return sections;
    }
}
