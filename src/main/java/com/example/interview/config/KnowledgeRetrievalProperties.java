package com.example.interview.config;

import com.example.interview.service.KnowledgeRetrievalMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 统一知识检索配置。
 *
 * <p>第一阶段默认保持 RAG_ONLY，以保证引入协调器后系统行为不变。
 * 等本地知识图链路稳定后，再将默认值切换到 LOCAL_GRAPH_FIRST。</p>
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
}
