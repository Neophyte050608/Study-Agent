package io.github.imzmq.interview.config.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Parent-Child 检索配置。
 *
 * <p>职责说明：</p>
 * <p>1. 统一管理 parent-child 建索引与检索回填阶段的核心参数。</p>
 * <p>2. 控制 child 切块大小、parent 最大长度以及检索阶段的回填范围。</p>
 * <p>3. 控制 parent 上下文与 child 命中片段的拼接长度，避免回填后上下文膨胀过快。</p>
 */
@Configuration
@ConfigurationProperties(prefix = "app.rag.parent-child")
public class ParentChildRetrievalProperties {

    /**
     * 是否启用 parent-child 检索链路。
     */
    private boolean enabled = true;

    /**
     * child 块目标大小。
     */
    private int childTargetSize = 220;

    /**
     * child 块重叠大小。
     */
    private int childOverlap = 40;

    /**
     * parent 文本的最大保留长度。
     */
    private int parentMaxSize = 1200;

    /**
     * 检索结果前 N 条允许执行 parent 回填。
     */
    private int hydrateParentTopN = 8;

    /**
     * 回填时最多保留多少字符的 parent 上下文。
     */
    private int hydrateParentContextChars = 420;

    /**
     * 回填时最多保留多少字符的 child 命中片段。
     */
    private int hydrateChildMatchChars = 180;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getChildTargetSize() {
        return childTargetSize;
    }

    public void setChildTargetSize(int childTargetSize) {
        this.childTargetSize = childTargetSize;
    }

    public int getChildOverlap() {
        return childOverlap;
    }

    public void setChildOverlap(int childOverlap) {
        this.childOverlap = childOverlap;
    }

    public int getParentMaxSize() {
        return parentMaxSize;
    }

    public void setParentMaxSize(int parentMaxSize) {
        this.parentMaxSize = parentMaxSize;
    }

    public int getHydrateParentTopN() {
        return hydrateParentTopN;
    }

    public void setHydrateParentTopN(int hydrateParentTopN) {
        this.hydrateParentTopN = hydrateParentTopN;
    }

    public int getHydrateParentContextChars() {
        return hydrateParentContextChars;
    }

    public void setHydrateParentContextChars(int hydrateParentContextChars) {
        this.hydrateParentContextChars = hydrateParentContextChars;
    }

    public int getHydrateChildMatchChars() {
        return hydrateChildMatchChars;
    }

    public void setHydrateChildMatchChars(int hydrateChildMatchChars) {
        this.hydrateChildMatchChars = hydrateChildMatchChars;
    }
}

