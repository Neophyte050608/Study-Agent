package io.github.imzmq.interview.media.api;

import io.github.imzmq.interview.media.application.contract.result.ImageMetadataResult;
import io.github.imzmq.interview.media.application.contract.extension.MediaSourcePathSupport;
import io.github.imzmq.interview.media.application.contract.extension.MediaSourcePathSupport.MediaSourcePaths;
import io.github.imzmq.interview.media.application.contract.MediaImageOperations;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/images")
public class ImageController {

    private final MediaImageOperations imageService;
    private final MediaSourcePathSupport mediaSourcePathSupport;

    public ImageController(MediaImageOperations imageService, MediaSourcePathSupport mediaSourcePathSupport) {
        this.imageService = imageService;
        this.mediaSourcePathSupport = mediaSourcePathSupport;
    }

    @GetMapping("/{imageId}/file")
    public ResponseEntity<Resource> getImageFile(@PathVariable String imageId) throws IOException {
        ImageMetadataResult metadata = requireImage(imageId);
        if (metadata == null) {
            return ResponseEntity.notFound().build();
        }
        Path path = Path.of(metadata.filePath());
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        Path resolved = resolveAndValidatePath(path);
        if (resolved == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok()
                .contentType(parseMediaType(metadata.mimeType()))
                .header(HttpHeaders.CACHE_CONTROL, "max-age=3600")
                .body(new FileSystemResource(resolved));
    }

    @GetMapping("/{imageId}/metadata")
    public ResponseEntity<Map<String, Object>> getImageMetadata(@PathVariable String imageId) {
        ImageMetadataResult metadata = requireImage(imageId);
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
        ImageMetadataResult metadata = requireImage(imageId);
        if (metadata == null) {
            return ResponseEntity.notFound().build();
        }
        Path path = Path.of(metadata.filePath());
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
        return ResponseEntity.ok(imageService.getImageMetadataByIds(imageIds).stream().map(this::toView).toList());
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

    private ImageMetadataResult requireImage(String imageId) {
        return imageService.getImageMetadataById(imageId);
    }

    private Map<String, Object> toView(ImageMetadataResult metadata) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("imageId", metadata.imageId());
        view.put("imageName", metadata.imageName());
        view.put("relativePath", metadata.relativePath());
        view.put("summaryText", metadata.summaryText());
        view.put("summaryStatus", metadata.summaryStatus());
        view.put("mimeType", metadata.mimeType());
        view.put("width", metadata.width());
        view.put("height", metadata.height());
        view.put("fileSize", metadata.fileSize());
        view.put("accessUrl", "/api/images/" + metadata.imageId() + "/file");
        view.put("thumbnailUrl", "/api/images/" + metadata.imageId() + "/thumbnail?maxWidth=400");
        return view;
    }

    private MediaType parseMediaType(String mimeType) {
        return MediaType.parseMediaType(mimeType);
    }

    private Path resolveAndValidatePath(Path path) throws IOException {
        Path resolved = path.toRealPath();
        for (Path allowedBase : allowedBasePaths()) {
            if (resolved.startsWith(allowedBase)) {
                return resolved;
            }
        }
        return null;
    }

    private List<Path> allowedBasePaths() throws IOException {
        MediaSourcePaths sourcePaths = mediaSourcePathSupport.currentSourcePaths();
        List<Path> allowed = new ArrayList<>();
        appendConfiguredPaths(allowed, sourcePaths.imagePath());
        appendConfiguredPaths(allowed, sourcePaths.vaultPaths());
        return allowed;
    }

    private void appendConfiguredPaths(List<Path> allowed, String raw) throws IOException {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String[] candidates = raw.split("\\R");
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            allowed.add(Path.of(candidate.trim()).toRealPath());
        }
    }
}






