package io.github.imzmq.interview.chat.infrastructure.milvus;

public record ChatMemoryVectorCandidate(
        String projectionId,
        long memoryId,
        long memoryVersion,
        String contentHash,
        String category,
        double score) {
}
