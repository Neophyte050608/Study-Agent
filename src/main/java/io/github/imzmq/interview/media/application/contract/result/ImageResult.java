package io.github.imzmq.interview.media.application.contract.result;

public record ImageResult(
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
