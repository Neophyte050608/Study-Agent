package io.github.imzmq.interview.chat.infrastructure.milvus;

public record ChatMemoryProjectionRow(
        String projectionId,
        long memoryId,
        String userId,
        long memoryVersion,
        String contentHash,
        String category) {
}
