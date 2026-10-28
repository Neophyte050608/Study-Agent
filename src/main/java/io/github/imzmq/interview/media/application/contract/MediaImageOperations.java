package io.github.imzmq.interview.media.application.contract;

import io.github.imzmq.interview.media.application.contract.result.ImageMetadataResult;
import io.github.imzmq.interview.media.application.contract.result.ImageResult;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Cross-module image enrichment, indexing, and retrieval operations. */
public interface MediaImageOperations {

    String enrichMarkdownWithImageSummaries(String markdown, String notePath);

    void indexImagesForDocument(String notePath, String markdown, List<MediaDocument> chunks);

    List<ImageResult> findImagesForDocuments(List<MediaDocument> retrievedDocs);

    List<ImageResult> searchRelevantImages(String query, boolean visualIntent);

    List<ImageResult> findImagesForNotePaths(Map<String, Double> notePathWeights, String query, String vaultRoot);

    Map<String, Object> getStatusSummary();

    java.util.concurrent.CompletableFuture<Map<String, Object>> reindexImages(boolean force);

    ImageMetadataResult getImageMetadataById(String imageId);

    List<ImageMetadataResult> getImageMetadataByIds(Collection<String> imageIds);
}
