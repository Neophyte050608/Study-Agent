package io.github.imzmq.interview.media.application;

import io.github.imzmq.interview.entity.media.ImageMetadataDO;
import io.github.imzmq.interview.mapper.media.ImageMetadataMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.MetricType;
import io.milvus.param.R;

import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.dml.UpsertParam;
import io.milvus.response.SearchResultsWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ImageIndexService {

    private static final Logger logger = LoggerFactory.getLogger(ImageIndexService.class);
    private static final int TEXT_DIMENSION = 2048;

    private final MilvusServiceClient milvusClient;
    private final ImageMetadataMapper imageMetadataMapper;
    private final ImageEmbeddingService imageEmbeddingService;
    private final ObjectMapper objectMapper;
    private final String collectionName;
    private final int visualDimension;

    public ImageIndexService(
            MilvusServiceClient milvusClient,
            ImageMetadataMapper imageMetadataMapper,
            ImageEmbeddingService imageEmbeddingService,
            ObjectMapper objectMapper,
            @Value("${app.multimodal.milvus.image-collection:interview_images}") String collectionName,
            @Value("${app.multimodal.visual.embedding-dimension:512}") int visualDimension) {
        this.milvusClient = milvusClient;
        this.imageMetadataMapper = imageMetadataMapper;
        this.imageEmbeddingService = imageEmbeddingService;
        this.objectMapper = objectMapper;
        this.collectionName = collectionName;
        this.visualDimension = visualDimension;
    }

    public void indexImage(ImageMetadataDO metadata, Path imagePath) {
        if (metadata == null || metadata.getImageId() == null || metadata.getImageId().isBlank()) {
            return;
        }
        try {
            ImageEmbeddingService.ImageEmbedding embedding = imageEmbeddingService.embed(metadata.getSummaryText(), imagePath);
            List<Float> textVector = normalizeVector(embedding.textEmbedding(), TEXT_DIMENSION);
            if (textVector.isEmpty()) {
                logger.warn("Skip Milvus image index because text embedding is empty: {}", metadata.getImageId());
                return;
            }
            List<Float> visualVector = imageEmbeddingService.isVisualEmbeddingEnabled()
                    ? normalizeVector(embedding.visualEmbedding(), visualDimension)
                    : zeroVector(visualDimension);
            String metadataJson = objectMapper.writeValueAsString(buildMetadataPayload(metadata));

            UpsertParam upsertParam = UpsertParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withFields(List.of(
                            InsertParam.Field.builder().name("image_id").values(List.of(metadata.getImageId())).build(),
                            InsertParam.Field.builder().name("text_embedding").values(List.of(textVector)).build(),
                            InsertParam.Field.builder().name("visual_embedding").values(List.of(visualVector)).build(),
                            InsertParam.Field.builder().name("metadata").values(List.of(metadataJson)).build()
                    ))
                    .build();
            R<MutationResult> upsertResult = milvusClient.upsert(upsertParam);
            if (upsertResult.getException() != null) {
                logger.warn("Milvus upsert image '{}' failed: {}", metadata.getImageId(), upsertResult.getException().getMessage());
                return;
            }

            metadata.setTextVectorId(metadata.getImageId());
            metadata.setVisualVectorId(imageEmbeddingService.isVisualEmbeddingEnabled() ? metadata.getImageId() : null);
            imageMetadataMapper.updateById(metadata);
        } catch (Exception e) {
            logger.warn("Index image '{}' into Milvus failed", metadata.getImageId(), e);
        }
    }

    public List<ImageHit> search(String query, int topK, boolean visualIntent) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isBlank()) {
            return List.of();
        }
        int limit = Math.max(1, topK);
        Map<String, ImageHit> merged = new LinkedHashMap<>();

        List<Float> textQueryVector = normalizeVector(
                imageEmbeddingService.embed(normalized, null).textEmbedding(),
                TEXT_DIMENSION
        );
        if (!textQueryVector.isEmpty()) {
            mergeHits(merged, doSearch("text_embedding", textQueryVector, limit, "text_embedding"));
        }

        if (visualIntent && imageEmbeddingService.isVisualEmbeddingEnabled()) {
            List<Float> visualQueryVector = normalizeVector(
                    imageEmbeddingService.embedVisualText(normalized),
                    visualDimension
            );
            if (!visualQueryVector.isEmpty()) {
                mergeHits(merged, doSearch("visual_embedding", visualQueryVector, limit, "visual_embedding"));
            }
        }

        return merged.values().stream()
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(limit)
                .toList();
    }

    private List<ImageHit> doSearch(String vectorFieldName, List<Float> queryVector, int topK, String retrieveChannel) {
        try {
            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withMetricType(MetricType.COSINE)
                    .withVectorFieldName(vectorFieldName)
                    .withFloatVectors(List.of(queryVector))
                    .withTopK(topK)
                    .withOutFields(List.of("image_id", "metadata"))
                    .withParams("{\"nprobe\":16}")
                    .build();
            R<SearchResults> response = milvusClient.search(searchParam);
            if (response.getException() != null || response.getData() == null) {
                if (response.getException() != null) {
                    logger.warn("Milvus search '{}' failed: {}", vectorFieldName, response.getException().getMessage());
                }
                return List.of();
            }
            SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
            List<SearchResultsWrapper.IDScore> scores = wrapper.getIDScore(0);
            List<ImageHit> hits = new ArrayList<>(scores.size());
            for (SearchResultsWrapper.IDScore score : scores) {
                String imageId = score.getStrID();
                Map<String, Object> metadata = parseMetadata(score.get("metadata"));
                String imageName = String.valueOf(metadata.getOrDefault("image_name", imageId));
                hits.add(new ImageHit(imageId, imageName, score.getScore(), retrieveChannel));
            }
            return hits;
        } catch (Exception e) {
            logger.warn("Milvus search '{}' failed", vectorFieldName, e);
            return List.of();
        }
    }

    private void mergeHits(Map<String, ImageHit> merged, List<ImageHit> hits) {
        for (ImageHit hit : hits) {
            merged.compute(hit.imageId(), (imageId, existing) -> {
                if (existing == null || hit.score() > existing.score()) {
                    return hit;
                }
                return existing;
            });
        }
    }

    private Map<String, Object> buildMetadataPayload(ImageMetadataDO metadata) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("image_name", metadata.getImageName());
        payload.put("relative_path", metadata.getRelativePath());
        payload.put("summary_snippet", summarize(metadata.getSummaryText()));
        payload.put("mime_type", metadata.getMimeType());
        return payload;
    }

    private Map<String, Object> parseMetadata(Object rawMetadata) {
        if (rawMetadata == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(String.valueOf(rawMetadata), new TypeReference<>() {
            });
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private String summarize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.length() <= 80 ? text : text.substring(0, 80);
    }

    private List<Float> normalizeVector(List<Double> vector, int dimension) {
        if (vector == null || vector.isEmpty()) {
            return List.of();
        }
        List<Float> normalized = new ArrayList<>(dimension);
        int size = Math.min(vector.size(), dimension);
        for (int i = 0; i < size; i++) {
            normalized.add(vector.get(i).floatValue());
        }
        while (normalized.size() < dimension) {
            normalized.add(0.0f);
        }
        return normalized;
    }

    private List<Float> zeroVector(int dimension) {
        List<Float> vector = new ArrayList<>(dimension);
        for (int i = 0; i < dimension; i++) {
            vector.add(0.0f);
        }
        return vector;
    }

    public record ImageHit(String imageId, String imageName, double score, String retrieveChannel) {
    }
}





