package com.example.interview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.interview.entity.ImageMetadataDO;
import com.example.interview.entity.RagParentDO;
import com.example.interview.entity.TextImageRelationDO;
import com.example.interview.mapper.ImageMetadataMapper;
import com.example.interview.mapper.RagParentMapper;
import com.example.interview.mapper.TextImageRelationMapper;
import com.example.interview.rag.ImageReferenceExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class ImageService {

    private static final Logger logger = LoggerFactory.getLogger(ImageService.class);

    private final ImageReferenceExtractor imageReferenceExtractor;
    private final ImageIngestionPipeline imageIngestionPipeline;
    private final ImageIndexService imageIndexService;
    private final ImageMetadataMapper imageMetadataMapper;
    private final TextImageRelationMapper textImageRelationMapper;
    private final RagParentMapper ragParentMapper;
    private final IngestConfigService ingestConfigService;
    private final int imageSearchTopK;

    public ImageService(ImageReferenceExtractor imageReferenceExtractor,
                        ImageIngestionPipeline imageIngestionPipeline,
                        ImageIndexService imageIndexService,
                        ImageMetadataMapper imageMetadataMapper,
                        TextImageRelationMapper textImageRelationMapper,
                        RagParentMapper ragParentMapper,
                        IngestConfigService ingestConfigService,
                        @org.springframework.beans.factory.annotation.Value("${app.multimodal.image-search-top-k:3}") int imageSearchTopK) {
        this.imageReferenceExtractor = imageReferenceExtractor;
        this.imageIngestionPipeline = imageIngestionPipeline;
        this.imageIndexService = imageIndexService;
        this.imageMetadataMapper = imageMetadataMapper;
        this.textImageRelationMapper = textImageRelationMapper;
        this.ragParentMapper = ragParentMapper;
        this.ingestConfigService = ingestConfigService;
        this.imageSearchTopK = imageSearchTopK;
    }

    public String enrichMarkdownWithImageSummaries(String markdown, String notePath) {
        List<ImageReferenceExtractor.ImageReference> refs = imageReferenceExtractor.extract(markdown, notePath, currentImageBasePath());
        return imageReferenceExtractor.embedSummaries(markdown, refs);
    }

    public void indexImagesForDocument(String notePath, String markdown, List<Document> chunks) {
        List<ImageReferenceExtractor.ImageReference> refs = imageReferenceExtractor.extract(markdown, notePath, currentImageBasePath());
        if (refs.isEmpty()) {
            clearRelationsForChunks(chunks);
            return;
        }

        Map<String, String> imageNameToId = new LinkedHashMap<>();
        List<CompletableFuture<ImageMetadataDO>> futures = new ArrayList<>();
        for (ImageReferenceExtractor.ImageReference ref : refs) {
            futures.add(imageIngestionPipeline.processImage(ref, resolveVaultPath(notePath), notePath));
        }
        for (CompletableFuture<ImageMetadataDO> future : futures) {
            try {
                ImageMetadataDO metadata = future.join();
                if (metadata != null) {
                    imageNameToId.put(metadata.getImageName().toLowerCase(Locale.ROOT), metadata.getImageId());
                }
            } catch (Exception e) {
                logger.warn("Join image ingestion future failed for notePath={}", notePath, e);
            }
        }

        clearRelationsForChunks(chunks);
        Map<String, Set<String>> parentToImages = new LinkedHashMap<>();
        LocalDateTime now = LocalDateTime.now();
        for (Document chunk : chunks) {
            if (chunk == null || chunk.getMetadata() == null || chunk.getText() == null) {
                continue;
            }
            String childId = stringMetadata(chunk, "child_id");
            if (childId == null || childId.isBlank()) {
                childId = chunk.getId();
            }
            String parentId = stringMetadata(chunk, "parent_id");
            if (parentId == null || parentId.isBlank()) {
                continue;
            }
            for (ImageReferenceExtractor.ImageReference ref : refs) {
                String imageId = imageNameToId.get(ref.imageName().toLowerCase(Locale.ROOT));
                if (imageId == null) {
                    continue;
                }
                boolean matched = chunk.getText().contains(ref.refSyntax()) || chunk.getText().contains(ref.imageName());
                if (!matched) {
                    continue;
                }
                TextImageRelationDO relation = new TextImageRelationDO();
                relation.setTextChunkId(childId);
                relation.setParentDocId(parentId);
                relation.setImageId(imageId);
                relation.setPositionInText(ref.position());
                relation.setRefSyntax(ref.refSyntax());
                relation.setCreatedAt(now);
                textImageRelationMapper.insert(relation);
                parentToImages.computeIfAbsent(parentId, key -> new LinkedHashSet<>()).add(imageId);
            }
        }
        updateParentsWithImageRefs(parentToImages);
    }

    public List<ImageResult> findImagesForDocuments(List<Document> retrievedDocs) {
        if (retrievedDocs == null || retrievedDocs.isEmpty()) {
            return List.of();
        }
        List<String> chunkIds = retrievedDocs.stream()
                .map(doc -> {
                    Object childId = doc.getMetadata().get("child_id");
                    return childId == null || String.valueOf(childId).isBlank() ? doc.getId() : String.valueOf(childId);
                })
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (chunkIds.isEmpty()) {
            return List.of();
        }
        List<TextImageRelationDO> relations = textImageRelationMapper.selectList(
                new LambdaQueryWrapper<TextImageRelationDO>()
                        .in(TextImageRelationDO::getTextChunkId, chunkIds)
        );
        if (relations.isEmpty()) {
            return List.of();
        }
        Map<String, Double> chunkScoreMap = retrievedDocs.stream().collect(Collectors.toMap(
                doc -> {
                    Object childId = doc.getMetadata().get("child_id");
                    return childId == null || String.valueOf(childId).isBlank() ? doc.getId() : String.valueOf(childId);
                },
                doc -> parseScore(doc.getMetadata().get("retrieval_score")),
                Math::max,
                LinkedHashMap::new
        ));
        List<String> imageIds = relations.stream().map(TextImageRelationDO::getImageId).distinct().toList();
        List<ImageMetadataDO> images = imageMetadataMapper.selectList(
                new LambdaQueryWrapper<ImageMetadataDO>().in(ImageMetadataDO::getImageId, imageIds)
        );
        Map<String, ImageMetadataDO> imageMap = images.stream().collect(Collectors.toMap(
                ImageMetadataDO::getImageId,
                image -> image,
                (left, right) -> left,
                LinkedHashMap::new
        ));
        Map<String, ImageResult> deduplicated = new LinkedHashMap<>();
        for (TextImageRelationDO relation : relations) {
            ImageMetadataDO image = imageMap.get(relation.getImageId());
            if (image == null) {
                continue;
            }
            double score = chunkScoreMap.getOrDefault(relation.getTextChunkId(), 0.55d);
            deduplicated.compute(image.getImageId(), (key, existing) -> {
                ImageResult candidate = new ImageResult(
                        image.getImageId(),
                        image.getImageName(),
                        "/api/images/" + image.getImageId() + "/file",
                        "/api/images/" + image.getImageId() + "/thumbnail?maxWidth=400",
                        image.getSummaryText(),
                        relation.getTextChunkId(),
                        score,
                        "text_associated"
                );
                if (existing == null || candidate.relevanceScore() > existing.relevanceScore()) {
                    return candidate;
                }
                return existing;
            });
        }
        return deduplicated.values().stream()
                .sorted((a, b) -> Double.compare(b.relevanceScore(), a.relevanceScore()))
                .limit(imageSearchTopK)
                .toList();
    }

    public List<ImageResult> searchRelevantImages(String query, boolean visualIntent) {
        List<ImageIndexService.ImageHit> hits = imageIndexService.search(query, imageSearchTopK, visualIntent);
        if (hits.isEmpty()) {
            return List.of();
        }
        List<ImageMetadataDO> images = getImagesByIds(hits.stream().map(ImageIndexService.ImageHit::imageId).toList());
        Map<String, ImageMetadataDO> imageMap = images.stream().collect(Collectors.toMap(
                ImageMetadataDO::getImageId,
                image -> image,
                (left, right) -> left,
                LinkedHashMap::new
        ));
        return hits.stream()
                .map(hit -> {
                    ImageMetadataDO metadata = imageMap.get(hit.imageId());
                    if (metadata == null) {
                        return null;
                    }
                    return new ImageResult(
                            metadata.getImageId(),
                            metadata.getImageName(),
                            "/api/images/" + metadata.getImageId() + "/file",
                            "/api/images/" + metadata.getImageId() + "/thumbnail?maxWidth=400",
                            metadata.getSummaryText(),
                            null,
                            hit.score(),
                            hit.retrieveChannel()
                    );
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(ImageResult::relevanceScore).reversed())
                .toList();
    }

    public Map<String, Object> getStatusSummary() {
        long pending = countByStatus("PENDING");
        long processing = countByStatus("PROCESSING");
        long completed = countByStatus("COMPLETED");
        long failed = countByStatus("FAILED");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pending", pending);
        result.put("processing", processing);
        result.put("completed", completed);
        result.put("failed", failed);
        result.put("total", pending + processing + completed + failed);
        return result;
    }

    public ImageMetadataDO getImageById(String imageId) {
        return imageMetadataMapper.selectOne(
                new LambdaQueryWrapper<ImageMetadataDO>()
                        .eq(ImageMetadataDO::getImageId, imageId)
                        .last("LIMIT 1")
        );
    }

    public List<ImageMetadataDO> getImagesByIds(Collection<String> imageIds) {
        if (imageIds == null || imageIds.isEmpty()) {
            return List.of();
        }
        return imageMetadataMapper.selectList(
                new LambdaQueryWrapper<ImageMetadataDO>().in(ImageMetadataDO::getImageId, imageIds)
        );
    }

    private void clearRelationsForChunks(List<Document> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        List<String> chunkIds = chunks.stream()
                .map(chunk -> chunk == null || chunk.getMetadata() == null ? null : stringMetadata(chunk, "child_id"))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (!chunkIds.isEmpty()) {
            textImageRelationMapper.delete(
                    new LambdaQueryWrapper<TextImageRelationDO>()
                            .in(TextImageRelationDO::getTextChunkId, chunkIds)
            );
        }
    }

    private void updateParentsWithImageRefs(Map<String, Set<String>> parentToImages) {
        if (parentToImages == null || parentToImages.isEmpty()) {
            return;
        }
        List<RagParentDO> parents = ragParentMapper.selectList(
                new LambdaQueryWrapper<RagParentDO>().in(RagParentDO::getParentId, parentToImages.keySet())
        );
        for (RagParentDO parent : parents) {
            parent.setImageRefs(new ArrayList<>(parentToImages.getOrDefault(parent.getParentId(), Set.of())));
            ragParentMapper.updateById(parent);
        }
    }

    private String resolveVaultPath(String notePath) {
        String configuredVaultPath = currentVaultPath();
        if (!configuredVaultPath.isBlank()) {
            return configuredVaultPath;
        }
        if (notePath == null || notePath.isBlank()) {
            return "";
        }
        try {
            return java.nio.file.Path.of(notePath).toAbsolutePath().getParent().toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String currentVaultPath() {
        return normalizeConfigValue(ingestConfigService.getConfig().get("paths"));
    }

    private String currentImageBasePath() {
        return normalizeConfigValue(ingestConfigService.getConfig().get("imagePath"));
    }

    private String normalizeConfigValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String[] parts = raw.split("\\R");
        return parts.length == 0 ? raw.trim() : parts[0].trim();
    }

    private String stringMetadata(Document document, String key) {
        Object value = document.getMetadata().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private double parseScore(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return 0.55d;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return 0.55d;
        }
    }

    private long countByStatus(String status) {
        return imageMetadataMapper.selectCount(
                new LambdaQueryWrapper<ImageMetadataDO>().eq(ImageMetadataDO::getSummaryStatus, status)
        );
    }

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
}
