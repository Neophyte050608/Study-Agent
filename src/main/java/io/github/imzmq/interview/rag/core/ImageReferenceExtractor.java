package io.github.imzmq.interview.rag.core;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ImageReferenceExtractor {

    private static final Pattern OBSIDIAN_PATTERN = Pattern.compile("!\\[\\[([^\\]]+?)\\]\\]");
    private static final Pattern MARKDOWN_PATTERN = Pattern.compile("!\\[[^\\]]*\\]\\(([^)]+)\\)");
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".png", ".jpg", ".jpeg", ".gif", ".webp");

    public List<ImageReference> extract(String markdown, String notePath, String imageBasePath) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        List<ImageReference> result = new ArrayList<>();
        collect(result, markdown, notePath, imageBasePath, OBSIDIAN_PATTERN, true);
        collect(result, markdown, notePath, imageBasePath, MARKDOWN_PATTERN, false);
        return result;
    }

    public String embedSummaries(String markdown, List<ImageReference> references) {
        if (markdown == null || markdown.isBlank() || references == null || references.isEmpty()) {
            return markdown;
        }
        StringBuilder builder = new StringBuilder(markdown);
        List<ImageReference> sorted = references.stream()
                .filter(reference -> reference.summaryText() != null && !reference.summaryText().isBlank())
                .sorted((left, right) -> Integer.compare(right.position(), left.position()))
                .toList();
        Set<Integer> seenPositions = new LinkedHashSet<>();
        for (ImageReference reference : sorted) {
            if (!seenPositions.add(reference.position())) {
                continue;
            }
            int start = reference.position();
            int end = Math.min(builder.length(), start + reference.refSyntax().length());
            if (start < 0 || start >= end) {
                continue;
            }
            builder.replace(start, end, reference.refSyntax() + "\n[图片摘要] " + reference.summaryText());
        }
        return builder.toString();
    }

    private void collect(List<ImageReference> result, String markdown, String notePath, String imageBasePath,
                         Pattern pattern, boolean obsidianStyle) {
        Matcher matcher = pattern.matcher(markdown);
        while (matcher.find()) {
            String rawTarget = matcher.group(1) == null ? "" : matcher.group(1).trim();
            String cleanedTarget = sanitizeTarget(rawTarget, obsidianStyle);
            if (!isSupportedImage(cleanedTarget)) {
                continue;
            }
            Path resolved = resolvePath(cleanedTarget, notePath, imageBasePath);
            String imageName = extractFileName(cleanedTarget);
            result.add(new ImageReference(
                    imageName,
                    cleanedTarget,
                    matcher.group(),
                    matcher.start(),
                    resolved,
                    resolveSectionPath(markdown, matcher.start()),
                    extractNearbyContext(markdown, matcher.start(), matcher.end()),
                    "图片：" + imageName
            ));
        }
    }

    private String resolveSectionPath(String markdown, int position) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        String[] lines = markdown.split("\\R", -1);
        List<String> currentPath = new ArrayList<>();
        int offset = 0;
        for (String line : lines) {
            if (offset > position) {
                break;
            }
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                int level = headingLevel(trimmed);
                if (level > 0) {
                    String heading = trimmed.substring(level).trim();
                    while (currentPath.size() >= level) {
                        currentPath.remove(currentPath.size() - 1);
                    }
                    if (!heading.isBlank()) {
                        currentPath.add(heading);
                    }
                }
            }
            offset += line.length() + 1;
        }
        return currentPath.isEmpty() ? "" : String.join(" > ", currentPath);
    }

    private int headingLevel(String line) {
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') {
            level++;
        }
        if (level == 0 || level >= line.length() || !Character.isWhitespace(line.charAt(level))) {
            return 0;
        }
        return level;
    }

    private String extractNearbyContext(String markdown, int start, int end) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        int safeStart = Math.max(0, start - 120);
        int safeEnd = Math.min(markdown.length(), end + 120);
        String snippet = markdown.substring(safeStart, safeEnd);
        return snippet.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private String sanitizeTarget(String target, boolean obsidianStyle) {
        String cleaned = target == null ? "" : target.trim();
        int pipe = cleaned.indexOf('|');
        if (obsidianStyle && pipe >= 0) {
            cleaned = cleaned.substring(0, pipe).trim();
        }
        if (cleaned.startsWith("<") && cleaned.endsWith(">") && cleaned.length() > 2) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        return cleaned;
    }

    private boolean isSupportedImage(String target) {
        String lower = target == null ? "" : target.toLowerCase(Locale.ROOT);
        return SUPPORTED_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private Path resolvePath(String imageTarget, String notePath, String imageBasePath) {
        Path target = Path.of(imageTarget);
        if (target.isAbsolute()) {
            return normalizePath(target);
        }
        if (notePath != null && !notePath.isBlank()) {
            try {
                Path sibling = Path.of(notePath).getParent().resolve(imageTarget).normalize();
                if (Files.exists(sibling)) {
                    return sibling;
                }
            } catch (Exception ignored) {
            }
        }
        if (imageBasePath != null && !imageBasePath.isBlank()) {
            Path byBase = Path.of(imageBasePath).resolve(imageTarget).normalize();
            if (Files.exists(byBase)) {
                return byBase;
            }
            Path byName = Path.of(imageBasePath).resolve(extractFileName(imageTarget)).normalize();
            if (Files.exists(byName)) {
                return byName;
            }
        }
        if (notePath != null && !notePath.isBlank()) {
            try {
                return Path.of(notePath).getParent().resolve(extractFileName(imageTarget)).normalize();
            } catch (Exception ignored) {
            }
        }
        return target.normalize();
    }

    private Path normalizePath(Path path) {
        try {
            return path.toRealPath();
        } catch (Exception ignored) {
            return path.normalize();
        }
    }

    private String extractFileName(String target) {
        try {
            return Path.of(target).getFileName().toString();
        } catch (Exception ignored) {
            return target;
        }
    }

    public record ImageReference(
            String imageName,
            String referencedPath,
            String refSyntax,
            int position,
            Path resolvedPath,
            String sectionPath,
            String nearbyContext,
            String summaryText
    ) {
    }
}

