package io.github.imzmq.interview.media.application;

import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;

@Component
public class ImageMetadataCollector {

    public CollectedImageMetadata collect(Path imagePath, String vaultPath) throws IOException {
        if (imagePath == null) {
            throw new IOException("imagePath is null");
        }
        Path normalized = imagePath.normalize();
        if (!Files.exists(normalized) || !Files.isRegularFile(normalized)) {
            throw new IOException("Image file not found: " + normalized);
        }
        String absolutePath = normalized.toAbsolutePath().toString();
        String imageId = sha256Hex(absolutePath);
        String mimeType = Files.probeContentType(normalized);
        long fileSize = Files.size(normalized);
        String fileHash;
        try (InputStream inputStream = Files.newInputStream(normalized)) {
            fileHash = DigestUtils.md5DigestAsHex(inputStream);
        }
        Integer width = null;
        Integer height = null;
        try {
            BufferedImage image = ImageIO.read(normalized.toFile());
            if (image != null) {
                width = image.getWidth();
                height = image.getHeight();
            }
        } catch (Exception ignored) {
        }
        String imageName = normalized.getFileName().toString();
        String relativePath = resolveRelativePath(normalized, vaultPath);
        return new CollectedImageMetadata(
                imageId,
                imageName,
                absolutePath,
                relativePath,
                "图片：" + imageName,
                "PENDING",
                normalizeMimeType(mimeType, imageName),
                width,
                height,
                fileSize,
                fileHash,
                LocalDateTime.now()
        );
    }

    private String resolveRelativePath(Path imagePath, String vaultPath) {
        if (vaultPath == null || vaultPath.isBlank()) {
            return imagePath.getFileName().toString();
        }
        try {
            Path vault = Path.of(vaultPath).toAbsolutePath().normalize();
            Path absolute = imagePath.toAbsolutePath().normalize();
            if (absolute.startsWith(vault)) {
                return vault.relativize(absolute).toString().replace("\\", "/");
            }
        } catch (Exception ignored) {
        }
        return imagePath.getFileName().toString();
    }

    private String normalizeMimeType(String mimeType, String imageName) {
        if (mimeType != null && !mimeType.isBlank()) {
            return mimeType;
        }
        String lower = imageName == null ? "" : imageName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return "application/octet-stream";
    }

    private String sha256Hex(String text) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IOException("Failed to calculate SHA-256 for image path", e);
        }
    }

    public record CollectedImageMetadata(
            String imageId,
            String imageName,
            String filePath,
            String relativePath,
            String summaryText,
            String summaryStatus,
            String mimeType,
            Integer width,
            Integer height,
            Long fileSize,
            String fileHash,
            LocalDateTime collectedAt
    ) {
    }
}




