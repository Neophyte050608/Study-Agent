package io.github.imzmq.interview.chat.application.memory;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Configuration
@ConfigurationProperties(prefix = "app.chat.memory")
@Validated
public class ChatMemoryProperties {

    private boolean enabled = false;
    @NotNull
    private Duration extractionDelay = Duration.ofSeconds(15);
    @Min(1) @Max(8)
    private int extractionBatchSize = 8;
    @Min(1) @Max(100_000)
    private int extractionInputCodePoints = 10_000;
    @Min(1) @Max(20)
    private int extractionMaxAttempts = 5;
    @NotBlank
    private String collectionName = "chat_memories";
    @Min(1) @Max(65_536)
    private int embeddingDimension = 2_048;
    @NotBlank
    private String embeddingModelId = "embedding-3";
    private boolean initializeSchema = true;
    @NotNull
    private Duration workerPoll = Duration.ofSeconds(1);
    @Min(1) @Max(500)
    private int workerBatchSize = 50;
    @NotNull
    private Duration workerLease = Duration.ofSeconds(60);
    @NotNull
    private Duration rpcTimeout = Duration.ofSeconds(10);
    @Min(1) @Max(50)
    private int projectionMaxAttempts = 10;
    @NotNull
    private Duration repairDelay = Duration.ofMinutes(30);
    @Min(1) @Max(1_000)
    private int repairPageSize = 200;
    @NotNull
    private Duration recallTimeout = Duration.ofSeconds(2);
    @Min(1) @Max(200)
    private int recallCandidates = 20;
    @Min(1) @Max(20)
    private int recallLimit = 6;
    @Min(1) @Max(5_000)
    private int recallEntryCodePoints = 500;
    @Min(100) @Max(20_000)
    private int recallContextCodePoints = 2_000;
    @Min(1) @Max(500)
    private int fallbackScanLimit = 50;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getExtractionDelay() { return extractionDelay; }
    public void setExtractionDelay(Duration extractionDelay) { this.extractionDelay = extractionDelay; }
    public int getExtractionBatchSize() { return extractionBatchSize; }
    public void setExtractionBatchSize(int extractionBatchSize) { this.extractionBatchSize = extractionBatchSize; }
    public int getExtractionInputCodePoints() { return extractionInputCodePoints; }
    public void setExtractionInputCodePoints(int extractionInputCodePoints) { this.extractionInputCodePoints = extractionInputCodePoints; }
    public int getExtractionMaxAttempts() { return extractionMaxAttempts; }
    public void setExtractionMaxAttempts(int extractionMaxAttempts) { this.extractionMaxAttempts = extractionMaxAttempts; }
    public String getCollectionName() { return collectionName; }
    public void setCollectionName(String collectionName) { this.collectionName = collectionName; }
    public int getEmbeddingDimension() { return embeddingDimension; }
    public void setEmbeddingDimension(int embeddingDimension) { this.embeddingDimension = embeddingDimension; }
    public String getEmbeddingModelId() { return embeddingModelId; }
    public void setEmbeddingModelId(String embeddingModelId) { this.embeddingModelId = embeddingModelId; }
    public boolean isInitializeSchema() { return initializeSchema; }
    public void setInitializeSchema(boolean initializeSchema) { this.initializeSchema = initializeSchema; }
    public Duration getWorkerPoll() { return workerPoll; }
    public void setWorkerPoll(Duration workerPoll) { this.workerPoll = workerPoll; }
    public int getWorkerBatchSize() { return workerBatchSize; }
    public void setWorkerBatchSize(int workerBatchSize) { this.workerBatchSize = workerBatchSize; }
    public Duration getWorkerLease() { return workerLease; }
    public void setWorkerLease(Duration workerLease) { this.workerLease = workerLease; }
    public Duration getRpcTimeout() { return rpcTimeout; }
    public void setRpcTimeout(Duration rpcTimeout) { this.rpcTimeout = rpcTimeout; }
    public int getProjectionMaxAttempts() { return projectionMaxAttempts; }
    public void setProjectionMaxAttempts(int projectionMaxAttempts) { this.projectionMaxAttempts = projectionMaxAttempts; }
    public Duration getRepairDelay() { return repairDelay; }
    public void setRepairDelay(Duration repairDelay) { this.repairDelay = repairDelay; }
    public int getRepairPageSize() { return repairPageSize; }
    public void setRepairPageSize(int repairPageSize) { this.repairPageSize = repairPageSize; }
    public Duration getRecallTimeout() { return recallTimeout; }
    public void setRecallTimeout(Duration recallTimeout) { this.recallTimeout = recallTimeout; }
    public int getRecallCandidates() { return recallCandidates; }
    public void setRecallCandidates(int recallCandidates) { this.recallCandidates = recallCandidates; }
    public int getRecallLimit() { return recallLimit; }
    public void setRecallLimit(int recallLimit) { this.recallLimit = recallLimit; }
    public int getRecallEntryCodePoints() { return recallEntryCodePoints; }
    public void setRecallEntryCodePoints(int recallEntryCodePoints) { this.recallEntryCodePoints = recallEntryCodePoints; }
    public int getRecallContextCodePoints() { return recallContextCodePoints; }
    public void setRecallContextCodePoints(int recallContextCodePoints) { this.recallContextCodePoints = recallContextCodePoints; }
    public int getFallbackScanLimit() { return fallbackScanLimit; }
    public void setFallbackScanLimit(int fallbackScanLimit) { this.fallbackScanLimit = fallbackScanLimit; }
}
