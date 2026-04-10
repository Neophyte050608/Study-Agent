package com.example.interview.controller;

import com.example.interview.entity.ImageMetadataDO;
import com.example.interview.service.IngestConfigService;
import com.example.interview.service.ImageService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/images")
public class ImageController {

    private final ImageService imageService;
    private final IngestConfigService ingestConfigService;

    public ImageController(ImageService imageService, IngestConfigService ingestConfigService) {
        this.imageService = imageService;
        this.ingestConfigService = ingestConfigService;
    }

    @GetMapping("/{imageId}/file")
    public ResponseEntity<Resource> getImageFile(@PathVariable String imageId) {
        ImageMetadataDO metadata = requireImage(imageId);
        if (metadata == null) {
            return ResponseEntity.notFound().build();
        }
        Path path = Path.of(metadata.getFilePath());
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        Path resolved = resolveAndValidatePath(path);
        if (resolved == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok()
                .contentType(parseMediaType(metadata.getMimeType()))
                .header(HttpHeaders.CACHE_CONTROL, "max-age=3600")
                .body(new FileSystemResource(resolved));
    }

    @GetMapping("/{imageId}/metadata")
    public ResponseEntity<Map<String, Object>> getImageMetadata(@PathVariable String imageId) {
        ImageMetadataDO metadata = requireImage(imageId);
        if (metadata == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toView(metadata));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getImageStatus() {
        return ResponseEntity.ok(imageService.getStatusSummary());
    }

    @GetMapping("/{imageId}/thumbnail")
    public ResponseEntity<Resource> getThumbnail(@PathVariable String imageId,
                                                 @RequestParam(defaultValue = "300") int maxWidth) throws Exception {
        ImageMetadataDO metadata = requireImage(imageId);
        if (metadata == null) {
            return ResponseEntity.notFound().build();
        }
        Path path = Path.of(metadata.getFilePath());
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        Path resolved = resolveAndValidatePath(path);
        if (resolved == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        maxWidth = Math.max(50, Math.min(maxWidth, 800));
        if (Files.size(resolved) > 10L * 1024L * 1024L) {
            return getImageFile(imageId);
        }
        BufferedImage source = ImageIO.read(resolved.toFile());
        if (source == null || source.getWidth() <= maxWidth) {
            return getImageFile(imageId);
        }
        int targetWidth = Math.max(1, maxWidth);
        int targetHeight = Math.max(1, (int) Math.round((double) source.getHeight() * targetWidth / source.getWidth()));
        BufferedImage resized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = resized.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        graphics.dispose();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(resized, "png", outputStream);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(new ByteArrayResource(outputStream.toByteArray()));
    }

    @PostMapping("/batch-metadata")
    public ResponseEntity<List<Map<String, Object>>> batchGetMetadata(@RequestBody List<String> imageIds) {
        return ResponseEntity.ok(imageService.getImagesByIds(imageIds).stream().map(this::toView).toList());
    }

    @PostMapping("/reindex")
    public ResponseEntity<Map<String, Object>> reindexImages(
            @RequestParam(defaultValue = "false") boolean force) {
        imageService.reindexImages(force);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "图片重索引任务已提交，请通过 GET /api/images/status 查看进度");
        response.put("force", force);
        return ResponseEntity.accepted().body(response);
    }

    private ImageMetadataDO requireImage(String imageId) {
        return imageService.getImageById(imageId);
    }

    private Map<String, Object> toView(ImageMetadataDO metadata) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("imageId", metadata.getImageId());
        view.put("imageName", metadata.getImageName());
        view.put("relativePath", metadata.getRelativePath());
        view.put("summaryText", metadata.getSummaryText());
        view.put("summaryStatus", metadata.getSummaryStatus());
        view.put("mimeType", metadata.getMimeType());
        view.put("width", metadata.getWidth());
        view.put("height", metadata.getHeight());
        view.put("fileSize", metadata.getFileSize());
        view.put("accessUrl", "/api/images/" + metadata.getImageId() + "/file");
        view.put("thumbnailUrl", "/api/images/" + metadata.getImageId() + "/thumbnail?maxWidth=400");
        return view;
    }

    private MediaType parseMediaType(String mimeType) {
        try {
            return MediaType.parseMediaType(mimeType);
        } catch (Exception ignored) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private Path resolveAndValidatePath(Path path) {
        try {
            Path resolved = path.toRealPath();
            for (Path allowedBase : allowedBasePaths()) {
                if (resolved.startsWith(allowedBase)) {
                    return resolved;
                }
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<Path> allowedBasePaths() {
        Map<String, String> config = ingestConfigService.getConfig();
        List<Path> allowed = new ArrayList<>();
        appendConfiguredPaths(allowed, config.get("imagePath"));
        appendConfiguredPaths(allowed, config.get("paths"));
        return allowed;
    }

    private void appendConfiguredPaths(List<Path> allowed, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String[] candidates = raw.split("\\R");
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            try {
                allowed.add(Path.of(candidate.trim()).toRealPath());
            } catch (Exception ignored) {
            }
        }
    }
}
