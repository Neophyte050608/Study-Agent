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
import java.nio.file.Paths;
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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ImageService {

    private static final Logger logger = LoggerFactory.getLogger(ImageService.class);
    private static final Path BROWSER_ASSET_ROOT = Paths.get("uploads", "browser-assets");
    private static final Pattern TOKEN_SPLIT_PATTERN = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final int MAX_CONTEXT_TOKENS = 8;
    private static final double MIN_ASSOCIATION_SCORE = 0.25d;

    private final ImageReferenceExtractor imageReferenceExtractor;
    private final ImageIngestionPipeline imageIngestionPipeline;
    private final ImageIndexService imageIndexService;
    private final ImageMetadataMapper imageMetadataMapper;
    private final TextImageRelationMapper textImageRelationMapper;
    private final RagParentMapper ragParentMapper;
    private final IngestConfigService ingestConfigService;
    private final int imageSearchTopK;
    private final double associatedImageMinScore;
    private final double semanticImageMinScore;

    public ImageService(ImageReferenceExtractor imageReferenceExtractor,
                        ImageIngestionPipeline imageIngestionPipeline,
                        ImageIndexService imageIndexService,
                        ImageMetadataMapper imageMetadataMapper,
                        TextImageRelationMapper textImageRelationMapper,
                        RagParentMapper ragParentMapper,
                        IngestConfigService ingestConfigService,
                        @org.springframework.beans.factory.annotation.Value("${app.multimodal.image-search-top-k:3}") int imageSearchTopK,
                        @org.springframework.beans.factory.annotation.Value("${app.multimodal.associated-image-min-score:0.45}") double associatedImageMinScore,
                        @org.springframework.beans.factory.annotation.Value("${app.multimodal.semantic-image-min-score:0.60}") double semanticImageMinScore) {
        this.imageReferenceExtractor = imageReferenceExtractor;
        this.imageIngestionPipeline = imageIngestionPipeline;
        this.imageIndexService = imageIndexService;
        this.imageMetadataMapper = imageMetadataMapper;
        this.textImageRelationMapper = textImageRelationMapper;
        this.ragParentMapper = ragParentMapper;
        this.ingestConfigService = ingestConfigService;
        this.imageSearchTopK = imageSearchTopK;
        this.associatedImageMinScore = associatedImageMinScore;
        this.semanticImageMinScore = semanticImageMinScore;
    }

    public String enrichMarkdownWithImageSummaries(String markdown, String notePath) {
        List<ImageReferenceExtractor.ImageReference> refs = imageReferenceExtractor.extract(markdown, notePath, resolveImageBasePath(notePath));
        refs = hydrateImageSummaries(refs, notePath);
        return imageReferenceExtractor.embedSummaries(markdown, refs);
    }

    public void indexImagesForDocument(String notePath, String markdown, List<Document> chunks) {
        List<ImageReferenceExtractor.ImageReference> refs = imageReferenceExtractor.extract(markdown, notePath, resolveImageBasePath(notePath));
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
                RelationScore relationScore = scoreChunkImageRelation(chunk, ref);
                if (relationScore.score() < MIN_ASSOCIATION_SCORE) {
                    continue;
                }
                TextImageRelationDO relation = new TextImageRelationDO();
                relation.setTextChunkId(childId);
                relation.setParentDocId(parentId);
                relation.setImageId(imageId);
                relation.setPositionInText(ref.position());
                relation.setRefSyntax(ref.refSyntax() + " ##score=" + String.format(Locale.ROOT, "%.3f", relationScore.score())
                        + " ##reason=" + relationScore.reason());
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
            double retrievalScore = chunkScoreMap.getOrDefault(relation.getTextChunkId(), 0.55d);
            double associationScore = parseAssociationScore(relation.getRefSyntax());
            double score = retrievalScore * 0.7d + associationScore * 0.3d;
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
                .filter(image -> image.relevanceScore() >= associatedImageMinScore)
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
                .filter(image -> image.relevanceScore() >= semanticImageMinScore)
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

    /**
     * 批量重索引图片：重新跑 VLM 摘要 + 向量索引。
     * @param force true=包含已 COMPLETED 的图片，false=只处理 PENDING/FAILED
     * @return 处理统计 {total, success, failed, skipped}
     */
    @org.springframework.scheduling.annotation.Async("imageIngestionExecutor")
    public java.util.concurrent.CompletableFuture<Map<String, Object>> reindexImages(boolean force) {
        List<ImageMetadataDO> targets;
        if (force) {
            targets = imageMetadataMapper.selectList(
                    new LambdaQueryWrapper<ImageMetadataDO>()
                            .orderByAsc(ImageMetadataDO::getId)
            );
        } else {
            targets = imageMetadataMapper.selectList(
                    new LambdaQueryWrapper<ImageMetadataDO>()
                            .in(ImageMetadataDO::getSummaryStatus, List.of("PENDING", "FAILED"))
                            .orderByAsc(ImageMetadataDO::getId)
            );
        }

        int success = 0;
        int failed = 0;
        int skipped = 0;
        for (ImageMetadataDO metadata : targets) {
            try {
                ImageMetadataDO result = imageIngestionPipeline.reprocessImage(metadata);
                if (result == null) {
                    skipped++;
                } else if ("COMPLETED".equals(result.getSummaryStatus())) {
                    success++;
                } else {
                    failed++;
                }
            } catch (Exception e) {
                logger.warn("Reindex image failed: {}", metadata.getImageId(), e);
                failed++;
            }
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", targets.size());
        stats.put("success", success);
        stats.put("failed", failed);
        stats.put("skipped", skipped);
        logger.info("Image reindex completed: {}", stats);
        return java.util.concurrent.CompletableFuture.completedFuture(stats);
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
        if (notePath != null && notePath.startsWith("browser://")) {
            return resolveImageBasePath(notePath);
        }
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

    private String resolveImageBasePath(String notePath) {
        String configured = currentImageBasePath();
        if (!configured.isBlank()) {
            return configured;
        }
        if (notePath != null && notePath.startsWith("browser://")) {
            int slash = notePath.indexOf('/', "browser://".length());
            String folderKey = slash < 0 ? notePath.substring("browser://".length()) : notePath.substring("browser://".length(), slash);
            if (!folderKey.isBlank()) {
                return BROWSER_ASSET_ROOT.resolve(folderKey).toAbsolutePath().normalize().toString();
            }
        }
        return "";
    }

    private List<ImageReferenceExtractor.ImageReference> hydrateImageSummaries(List<ImageReferenceExtractor.ImageReference> refs, String notePath) {
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }
        List<ImageReferenceExtractor.ImageReference> hydrated = new ArrayList<>(refs.size());
        String vaultPath = resolveVaultPath(notePath);
        for (ImageReferenceExtractor.ImageReference ref : refs) {
            String summary = ref.summaryText();
            try {
                ImageMetadataDO metadata = imageIngestionPipeline.processImageSync(ref, vaultPath, notePath);
                if (metadata != null && metadata.getSummaryText() != null && !metadata.getSummaryText().isBlank()) {
                    summary = metadata.getSummaryText();
                }
            } catch (Exception e) {
                logger.debug("Hydrate image summary failed for notePath={}, image={}", notePath, ref.imageName(), e);
            }
            hydrated.add(new ImageReferenceExtractor.ImageReference(
                    ref.imageName(),
                    ref.referencedPath(),
                    ref.refSyntax(),
                    ref.position(),
                    ref.resolvedPath(),
                    ref.sectionPath(),
                    ref.nearbyContext(),
                    summary
            ));
        }
        return hydrated;
    }

    private RelationScore scoreChunkImageRelation(Document chunk, ImageReferenceExtractor.ImageReference ref) {
        String chunkText = normalizeText(chunk.getText());
        String sectionPath = normalizeText(stringMetadata(chunk, "section_path"));
        String chunkBody = normalizeText(stripMetadataPrefix(chunk.getText()));

        double score = 0.0d;
        List<String> reasons = new ArrayList<>();

        // 1. 精确引用匹配（图片语法出现在 chunk 中）
        if (chunkText.contains(normalizeText(ref.refSyntax()))) {
            score += 0.60d;
            reasons.add("exact_ref");
        }

        // 2. 图片摘要文本匹配（enrichMarkdownWithImageSummaries 替换后的 [图片摘要] 文本）
        String summaryMarker = normalizeText("[图片摘要] " + (ref.summaryText() == null ? "" : ref.summaryText()));
        if (!summaryMarker.isBlank() && chunkText.contains(summaryMarker)) {
            score += 0.50d;
            reasons.add("summary_marker");
        }

        // 3. 文件名匹配
        if (chunkText.contains(normalizeText(ref.imageName()))) {
            score += 0.30d;
            reasons.add("image_name");
        }

        // 4. 章节路径匹配（增强版：分级比较）
        String refSection = normalizeText(ref.sectionPath());
        if (!refSection.isBlank() && !sectionPath.isBlank()) {
            if (sectionPath.equals(refSection)) {
                score += 0.30d;
                reasons.add("same_section");
            } else if (sectionPath.startsWith(refSection) || refSection.startsWith(sectionPath)) {
                score += 0.20d;
                reasons.add("parent_section");
            } else if (sectionPath.contains(refSection) || refSection.contains(sectionPath)) {
                score += 0.12d;
                reasons.add("near_section");
            }
        }

        // 5. 上下文词汇重叠
        double contextOverlap = overlapScore(chunkBody, ref.nearbyContext(), MAX_CONTEXT_TOKENS);
        if (contextOverlap > 0) {
            score += 0.25d * contextOverlap;
            reasons.add("near_context");
        }

        // 6. VLM 摘要与 chunk 内容重叠
        double summaryOverlap = overlapScore(chunkBody, ref.summaryText(), MAX_CONTEXT_TOKENS);
        if (summaryOverlap > 0) {
            score += 0.20d * summaryOverlap;
            reasons.add("summary_overlap");
        }

        return new RelationScore(Math.min(1.0d, score), reasons.isEmpty() ? "weak_match" : String.join("+", reasons));
    }

    private double overlapScore(String text, String candidate, int maxTokens) {
        Set<String> textTokens = tokenize(text, maxTokens * 3);
        Set<String> candidateTokens = tokenize(candidate, maxTokens);
        if (textTokens.isEmpty() || candidateTokens.isEmpty()) {
            return 0.0d;
        }
        long matched = candidateTokens.stream().filter(textTokens::contains).count();
        return matched == 0 ? 0.0d : Math.min(1.0d, matched / (double) candidateTokens.size());
    }

    private Set<String> tokenize(String text, int limit) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String[] parts = TOKEN_SPLIT_PATTERN.split(normalizeText(text));
        Set<String> tokens = new LinkedHashSet<>();
        for (String part : parts) {
            if (part == null || part.isBlank() || part.length() < 2) {
                continue;
            }
            tokens.add(part);
            if (tokens.size() >= limit) {
                break;
            }
        }
        return tokens;
    }

    private String normalizeText(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
    }

    private String stripMetadataPrefix(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        int newline = text.indexOf('\n');
        if (newline <= 0) {
            return text;
        }
        String firstLine = text.substring(0, newline);
        if (firstLine.startsWith("[文档:") || firstLine.startsWith("[章节:") || firstLine.startsWith("[来源:")) {
            return text.substring(newline + 1);
        }
        return text;
    }

    private double parseAssociationScore(String refSyntax) {
        if (refSyntax == null || refSyntax.isBlank()) {
            return 0.55d;
        }
        int marker = refSyntax.indexOf("##score=");
        if (marker < 0) {
            return 0.55d;
        }
        int start = marker + "##score=".length();
        int end = refSyntax.indexOf(' ', start);
        String raw = end < 0 ? refSyntax.substring(start) : refSyntax.substring(start, end);
        try {
            return Double.parseDouble(raw.trim());
        } catch (Exception ignored) {
            return 0.55d;
        }
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

    private record RelationScore(double score, String reason) {
    }
}
