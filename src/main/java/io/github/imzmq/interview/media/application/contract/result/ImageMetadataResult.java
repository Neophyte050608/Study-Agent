package io.github.imzmq.interview.media.application.contract.result;

/** Controller-facing image metadata view exposed by the media application contract. */
public record ImageMetadataResult(
        String imageId,
        String imageName,
        String relativePath,
        String filePath,
        String summaryText,
        String summaryStatus,
        String mimeType,
        Integer width,
        Integer height,
        Long fileSize
) {
}
