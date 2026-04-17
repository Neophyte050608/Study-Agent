package io.github.imzmq.interview.config.knowledge;

import io.github.imzmq.interview.knowledge.domain.KnowledgeRetrievalMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 统一知识检索配置。
 *
 * <p>默认保持 RAG_ONLY，避免配置升级时意外放大检索成本。
 * 如需更强召回，可显式切换到 HYBRID_FUSION。</p>
 */
@Component
@ConfigurationProperties(prefix = "app.knowledge.retrieval")
public class KnowledgeRetrievalProperties {

    private KnowledgeRetrievalMode defaultMode = KnowledgeRetrievalMode.RAG_ONLY;
    private String indexFilePath = "";
    private int requiredSchemaVersion = 1;
    private int candidateRecallTopN = 20;
    private int maxLocalMatches = 3;
    private int maxLinkedNotes = 2;
    private int maxBacklinkNotes = 2;
    private int maxTagNeighborNotes = 2;
    private int minLocalContextChars = 200;
    private int maxNoteChars = 4000;
    private int localContextBudgetChars = 12000;
    private String ollamaBaseUrl = "http://localhost:11434";
    private String ollamaModel = "";
    private long ollamaTimeoutMs = 5000L;
    private boolean retrievalCacheEnabled = true;
    private int retrievalCacheTtlSeconds = 20;
    private boolean warmupEnabled = true;

    public KnowledgeRetrievalMode getDefaultMode() {
        return defaultMode;
    }

    public void setDefaultMode(KnowledgeRetrievalMode defaultMode) {
        this.defaultMode = defaultMode == null ? KnowledgeRetrievalMode.RAG_ONLY : defaultMode;
    }

    public String getIndexFilePath() {
        return indexFilePath;
    }

    public void setIndexFilePath(String indexFilePath) {
        this.indexFilePath = indexFilePath == null ? "" : indexFilePath.trim();
    }

    public int getRequiredSchemaVersion() {
        return requiredSchemaVersion;
    }

    public void setRequiredSchemaVersion(int requiredSchemaVersion) {
        this.requiredSchemaVersion = Math.max(1, requiredSchemaVersion);
    }

    public int getCandidateRecallTopN() {
        return candidateRecallTopN;
    }

    public void setCandidateRecallTopN(int candidateRecallTopN) {
        this.candidateRecallTopN = Math.max(1, candidateRecallTopN);
    }

    public int getMaxLocalMatches() {
        return maxLocalMatches;
    }

    public void setMaxLocalMatches(int maxLocalMatches) {
        this.maxLocalMatches = Math.max(1, maxLocalMatches);
    }

    public int getMaxLinkedNotes() {
        return maxLinkedNotes;
    }

    public void setMaxLinkedNotes(int maxLinkedNotes) {
        this.maxLinkedNotes = Math.max(0, maxLinkedNotes);
    }

    public int getMaxBacklinkNotes() {
        return maxBacklinkNotes;
    }

    public void setMaxBacklinkNotes(int maxBacklinkNotes) {
        this.maxBacklinkNotes = Math.max(0, maxBacklinkNotes);
    }

    public int getMaxTagNeighborNotes() {
        return maxTagNeighborNotes;
    }

    public void setMaxTagNeighborNotes(int maxTagNeighborNotes) {
        this.maxTagNeighborNotes = Math.max(0, maxTagNeighborNotes);
    }

    public int getMinLocalContextChars() {
        return minLocalContextChars;
    }

    public void setMinLocalContextChars(int minLocalContextChars) {
        this.minLocalContextChars = Math.max(1, minLocalContextChars);
    }

    public int getMaxNoteChars() {
        return maxNoteChars;
    }

    public void setMaxNoteChars(int maxNoteChars) {
        this.maxNoteChars = Math.max(200, maxNoteChars);
    }

    public int getLocalContextBudgetChars() {
        return localContextBudgetChars;
    }

    public void setLocalContextBudgetChars(int localContextBudgetChars) {
        this.localContextBudgetChars = Math.max(1000, localContextBudgetChars);
    }

    public String getOllamaBaseUrl() {
        return ollamaBaseUrl;
    }

    public void setOllamaBaseUrl(String ollamaBaseUrl) {
        this.ollamaBaseUrl = ollamaBaseUrl == null ? "http://localhost:11434" : ollamaBaseUrl.trim();
    }

    public String getOllamaModel() {
        return ollamaModel;
    }

    public void setOllamaModel(String ollamaModel) {
        this.ollamaModel = ollamaModel == null ? "" : ollamaModel.trim();
    }

    public long getOllamaTimeoutMs() {
        return ollamaTimeoutMs;
    }

    public void setOllamaTimeoutMs(long ollamaTimeoutMs) {
        this.ollamaTimeoutMs = Math.max(1000L, ollamaTimeoutMs);
    }

    public boolean isRetrievalCacheEnabled() {
        return retrievalCacheEnabled;
    }

    public void setRetrievalCacheEnabled(boolean retrievalCacheEnabled) {
        this.retrievalCacheEnabled = retrievalCacheEnabled;
    }

    public int getRetrievalCacheTtlSeconds() {
        return retrievalCacheTtlSeconds;
    }

    public void setRetrievalCacheTtlSeconds(int retrievalCacheTtlSeconds) {
        this.retrievalCacheTtlSeconds = Math.max(1, retrievalCacheTtlSeconds);
    }

    public boolean isWarmupEnabled() {
        return warmupEnabled;
    }

    public void setWarmupEnabled(boolean warmupEnabled) {
        this.warmupEnabled = warmupEnabled;
    }
}


