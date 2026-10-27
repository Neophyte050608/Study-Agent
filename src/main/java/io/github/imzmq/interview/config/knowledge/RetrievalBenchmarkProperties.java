package io.github.imzmq.interview.config.knowledge;

import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkOperation;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** Safe configuration for explicitly enabled isolated retrieval benchmark operations. */
@Component
@ConfigurationProperties(prefix = "app.knowledge.retrieval-benchmark")
public class RetrievalBenchmarkProperties {

    private boolean enabled;
    private BenchmarkOperation operation = BenchmarkOperation.VALIDATE;
    private String datasetId = "";
    private String goldFile = "";
    private String corpusFile = "";
    private String outputFile = "";
    private String checkpointFile = "";
    private String collectionName = "study_agent_retrieval_benchmark";
    private int batchSize = 64;
    private String embeddingModelId = "embedding-3";
    private int embeddingDimension = 2048;
    private String cleanupConfirmation = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public BenchmarkOperation getOperation() { return operation; }
    public void setOperation(BenchmarkOperation operation) {
        this.operation = operation == null ? BenchmarkOperation.VALIDATE : operation;
    }
    public String getDatasetId() { return datasetId; }
    public void setDatasetId(String datasetId) { this.datasetId = normalize(datasetId); }
    public String getCheckpointFile() { return checkpointFile; }
    public void setCheckpointFile(String checkpointFile) { this.checkpointFile = normalize(checkpointFile); }
    public String getCollectionName() { return collectionName; }
    public void setCollectionName(String collectionName) { this.collectionName = normalize(collectionName); }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public String getEmbeddingModelId() { return embeddingModelId; }
    public void setEmbeddingModelId(String embeddingModelId) { this.embeddingModelId = normalize(embeddingModelId); }
    public int getEmbeddingDimension() { return embeddingDimension; }
    public void setEmbeddingDimension(int embeddingDimension) { this.embeddingDimension = embeddingDimension; }
    public String getCleanupConfirmation() { return cleanupConfirmation; }
    public void setCleanupConfirmation(String cleanupConfirmation) {
        this.cleanupConfirmation = normalize(cleanupConfirmation);
    }

    public String getGoldFile() {
        return goldFile;
    }

    public void setGoldFile(String goldFile) {
        this.goldFile = normalize(goldFile);
    }

    public String getCorpusFile() {
        return corpusFile;
    }

    public void setCorpusFile(String corpusFile) {
        this.corpusFile = normalize(corpusFile);
    }

    public String getOutputFile() {
        return outputFile;
    }

    public void setOutputFile(String outputFile) {
        this.outputFile = normalize(outputFile);
    }

    public Path requireGoldPath() {
        return requirePath(goldFile, "gold-file");
    }

    public Path requireCorpusPath() {
        return requirePath(corpusFile, "corpus-file");
    }

    public Path requireCheckpointPath() {
        return requirePath(checkpointFile, "checkpoint-file");
    }

    public Path requireOutputPath() {
        return requirePath(outputFile, "output-file");
    }

    private Path requirePath(String value, String property) {
        if (value.isBlank()) {
            throw new IllegalStateException(
                    "app.knowledge.retrieval-benchmark." + property + " must be configured"
            );
        }
        return Path.of(value);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
