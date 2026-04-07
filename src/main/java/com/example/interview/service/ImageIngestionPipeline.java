package com.example.interview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.interview.entity.ImageMetadataDO;
import com.example.interview.mapper.ImageMetadataMapper;
import com.example.interview.rag.ImageReferenceExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

@Service
public class ImageIngestionPipeline {

    private static final Logger logger = LoggerFactory.getLogger(ImageIngestionPipeline.class);

    private final ImageMetadataCollector imageMetadataCollector;
    private final VisionModelService visionModelService;
    private final ImageMetadataMapper imageMetadataMapper;
    private final ImageIndexService imageIndexService;

    public ImageIngestionPipeline(ImageMetadataCollector imageMetadataCollector,
                                  VisionModelService visionModelService,
                                  ImageMetadataMapper imageMetadataMapper,
                                  ImageIndexService imageIndexService) {
        this.imageMetadataCollector = imageMetadataCollector;
        this.visionModelService = visionModelService;
        this.imageMetadataMapper = imageMetadataMapper;
        this.imageIndexService = imageIndexService;
    }

    @Async("imageIngestionExecutor")
    public CompletableFuture<ImageMetadataDO> processImage(ImageReferenceExtractor.ImageReference ref, String vaultPath, String notePath) {
        return CompletableFuture.completedFuture(processImageSync(ref, vaultPath, notePath));
    }

    public ImageMetadataDO processImageSync(ImageReferenceExtractor.ImageReference ref, String vaultPath, String notePath) {
        try {
            if (ref.resolvedPath() == null || !Files.exists(ref.resolvedPath())) {
                logger.warn("Referenced image not found, notePath={}, target={}", notePath, ref.referencedPath());
                return null;
            }
            ImageMetadataCollector.CollectedImageMetadata collected =
                    imageMetadataCollector.collect(ref.resolvedPath(), vaultPath);
            ImageMetadataDO existing = imageMetadataMapper.selectOne(
                    new LambdaQueryWrapper<ImageMetadataDO>()
                            .eq(ImageMetadataDO::getImageId, collected.imageId())
                            .last("LIMIT 1")
            );
            if (existing != null
                    && collected.fileHash().equals(existing.getFileHash())
                    && "COMPLETED".equals(existing.getSummaryStatus())) {
                logger.debug("Image unchanged, skipping: {}", ref.resolvedPath());
                return existing;
            }
            ImageMetadataDO metadata = createOrUpdatePendingMetadata(collected);
            metadata.setSummaryStatus("PROCESSING");
            imageMetadataMapper.updateById(metadata);
            String summary = visionModelService.summarize(ref.resolvedPath(), ref.imageName());
            metadata.setSummaryText(summary);
            metadata.setSummaryStatus("COMPLETED");
            imageMetadataMapper.updateById(metadata);
            imageIndexService.indexImage(metadata, ref.resolvedPath());
            return metadata;
        } catch (IOException e) {
            logger.warn("Collect image metadata failed, notePath={}, target={}", notePath, ref.referencedPath(), e);
            return null;
        } catch (Exception e) {
            logger.warn("Image async processing failed, notePath={}, target={}", notePath, ref.referencedPath(), e);
            return markFailed(ref);
        }
    }

    private ImageMetadataDO createOrUpdatePendingMetadata(ImageMetadataCollector.CollectedImageMetadata collected) {
        ImageMetadataDO existing = imageMetadataMapper.selectOne(
                new LambdaQueryWrapper<ImageMetadataDO>()
                        .eq(ImageMetadataDO::getImageId, collected.imageId())
                        .last("LIMIT 1")
        );
        if (existing == null) {
            ImageMetadataDO created = new ImageMetadataDO();
            created.setImageId(collected.imageId());
            created.setImageName(collected.imageName());
            created.setFilePath(collected.filePath());
            created.setRelativePath(collected.relativePath());
            created.setSummaryText(collected.summaryText());
            created.setSummaryStatus("PENDING");
            created.setMimeType(collected.mimeType());
            created.setWidth(collected.width());
            created.setHeight(collected.height());
            created.setFileSize(collected.fileSize());
            created.setFileHash(collected.fileHash());
            created.setCreatedAt(collected.collectedAt());
            created.setUpdatedAt(collected.collectedAt());
            imageMetadataMapper.insert(created);
            return created;
        }
        existing.setImageName(collected.imageName());
        existing.setFilePath(collected.filePath());
        existing.setRelativePath(collected.relativePath());
        existing.setMimeType(collected.mimeType());
        existing.setWidth(collected.width());
        existing.setHeight(collected.height());
        existing.setFileSize(collected.fileSize());
        existing.setFileHash(collected.fileHash());
        existing.setSummaryStatus("PENDING");
        imageMetadataMapper.updateById(existing);
        return existing;
    }

    private ImageMetadataDO markFailed(ImageReferenceExtractor.ImageReference ref) {
        if (ref == null || ref.resolvedPath() == null) {
            return null;
        }
        try {
            ImageMetadataCollector.CollectedImageMetadata collected =
                    imageMetadataCollector.collect(ref.resolvedPath(), "");
            ImageMetadataDO metadata = createOrUpdatePendingMetadata(collected);
            metadata.setSummaryStatus("FAILED");
            metadata.setSummaryText("图片：" + ref.imageName());
            imageMetadataMapper.updateById(metadata);
            return metadata;
        } catch (Exception ignored) {
            return null;
        }
    }
}
