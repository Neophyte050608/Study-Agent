package io.github.imzmq.interview.knowledge.domain;

/**
 * Public contract for image evidence returned with a knowledge context packet.
 */
public record KnowledgeImageResult(
        String imageId,
        String imageName,
        String accessUrl,
        String thumbnailUrl,
        String summaryText,
        String sourceChunkId,
        double relevanceScore,
        String retrieveChannel
) {
}
