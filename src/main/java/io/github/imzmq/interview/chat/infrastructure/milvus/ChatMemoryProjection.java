package io.github.imzmq.interview.chat.infrastructure.milvus;

public record ChatMemoryProjection(
        String projectionId,
        Long memoryId,
        String userId,
        Long memoryVersion,
        String contentHash,
        String category,
        String content) {
}
