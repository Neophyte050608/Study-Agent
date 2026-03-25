package com.example.interview.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * 表示 Markdown 文档中的一个逻辑结构段落 (Section)。
 * 通常由标题 (Heading) 及其下方的内容组成。
 */
public class MarkdownSection {

    /** 所在章节的层级深度 (如 1 代表 #, 2 代表 ##) */
    private int level;

    /** 章节的当前标题 */
    private String heading;

    /** 从根节点到当前章节的标题路径，如 "Java基础 > 集合框架 > HashMap" */
    private List<String> headingPath;

    /** 该章节包含的原始文本内容 (不包含标题本身) */
    private StringBuilder contentBuilder = new StringBuilder();

    public MarkdownSection(int level, String heading, List<String> headingPath) {
        this.level = level;
        this.heading = heading;
        this.headingPath = new ArrayList<>(headingPath);
    }

    public void appendLine(String line) {
        contentBuilder.append(line).append("\n");
    }

    public String getContent() {
        return contentBuilder.toString().trim();
    }

    public int getLevel() {
        return level;
    }

    public String getHeading() {
        return heading;
    }

    public List<String> getHeadingPath() {
        return headingPath;
    }

    /**
     * 将层级路径格式化为面包屑字符串。
     * @return 形如 "H1 > H2 > H3" 的字符串
     */
    public String getFormattedPath() {
        if (headingPath == null || headingPath.isEmpty()) {
            return heading;
        }
        return String.join(" > ", headingPath);
    }
}
