package io.github.imzmq.interview.config.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 检索阶段配置。
 *
 * <p>本配置目前主要用于管理 Web fallback 的触发策略，避免“本地有结果但质量明显偏低”时仍然直接进入评估。</p>
 */
@Component
@ConfigurationProperties(prefix = "app.rag.retrieval")
public class RagRetrievalProperties {

    /**
     * 词法检索模式。
     * AUTO：优先使用 FULLTEXT，SQL 不兼容或索引缺失时回退到 LIKE。
     * FULLTEXT：仅使用 FULLTEXT。
     * LIKE：仅使用历史 LIKE 方案。
     */
    private LexicalSearchMode lexicalSearchMode = LexicalSearchMode.AUTO;

    /**
     * Web fallback 模式。
     * NONE：完全禁用。
     * ON_EMPTY：仅当本地检索上下文为空时触发。
     * ON_LOW_QUALITY：当上下文为空或最佳重排分数低于阈值时触发。
     */
    private WebFallbackMode webFallbackMode = WebFallbackMode.ON_EMPTY;

    /**
     * 当 fallback 模式为 ON_LOW_QUALITY 时，用于判定“本地结果质量偏低”的阈值。
     */
    private double webFallbackQualityThreshold = 0.10D;

    public LexicalSearchMode getLexicalSearchMode() {
        return lexicalSearchMode;
    }

    public void setLexicalSearchMode(LexicalSearchMode lexicalSearchMode) {
        this.lexicalSearchMode = lexicalSearchMode == null ? LexicalSearchMode.AUTO : lexicalSearchMode;
    }

    public WebFallbackMode getWebFallbackMode() {
        return webFallbackMode;
    }

    public void setWebFallbackMode(WebFallbackMode webFallbackMode) {
        this.webFallbackMode = webFallbackMode == null ? WebFallbackMode.ON_EMPTY : webFallbackMode;
    }

    public double getWebFallbackQualityThreshold() {
        return webFallbackQualityThreshold;
    }

    public void setWebFallbackQualityThreshold(double webFallbackQualityThreshold) {
        this.webFallbackQualityThreshold = Math.max(0.0D, webFallbackQualityThreshold);
    }

    public enum WebFallbackMode {
        NONE,
        ON_EMPTY,
        ON_LOW_QUALITY
    }

    public enum LexicalSearchMode {
        AUTO,
        FULLTEXT,
        LIKE
    }
}

