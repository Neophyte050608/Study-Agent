package io.github.imzmq.interview.knowledge.application.indexing;

import io.github.imzmq.interview.config.knowledge.KnowledgeRetrievalProperties;
import io.github.imzmq.interview.rag.core.NoteLoader;
import io.github.imzmq.interview.ingestion.application.IngestConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地知识索引生成服务。
 *
 * <p>复用现有 Obsidian Markdown 扫描能力，产出供 Local Graph 检索链路直接消费的 JSON 索引。</p>
 */
@Service
public class LocalKnowledgeIndexBuildService {

    private static final Pattern FRONTMATTER_BOUNDARY = Pattern.compile("^---\\s*$");
    private static final Pattern HEADING_PATTERN = Pattern.compile("^#{1,6}\\s+(.*)$");
    private static final Pattern TAG_PATTERN = Pattern.compile("(^|\\s)#([\\p{L}\\p{N}_/-]{2,})");
    private static final Pattern WIKI_LINK_PATTERN = Pattern.compile("\\[\\[([^\\]|#]+)(?:#[^\\]|]+)?(?:\\|.*?)?\\]\\]");

    private final NoteLoader noteLoader;
    private final KnowledgeRetrievalProperties properties;
    private final IngestConfigService ingestConfigService;
    private final LocalKnowledgeScopeService localKnowledgeScopeService;
    private final ObjectMapper objectMapper;

    public LocalKnowledgeIndexBuildService(NoteLoader noteLoader,
                                           KnowledgeRetrievalProperties properties,
                                           IngestConfigService ingestConfigService,
                                           LocalKnowledgeScopeService localKnowledgeScopeService,
                                           ObjectMapper objectMapper) {
        this.noteLoader = noteLoader;
        this.properties = properties;
        this.ingestConfigService = ingestConfigService;
        this.localKnowledgeScopeService = localKnowledgeScopeService;
        this.objectMapper = objectMapper;
    }

    public IndexBuildResult build(IndexBuildRequest request) {
        BuildScope scope = resolveBuildScope(request);
        String vaultPath = scope.vaultPath();
        List<String> includeDirs = scope.includeDirs();
        List<String> ignoredDirs = scope.ignoredDirs();
        Path vaultRoot = Paths.get(vaultPath).toAbsolutePath().normalize();
        validateVaultRoot(vaultRoot);

        List<Resource> resources = noteLoader.loadNotes(vaultRoot.toString(), includeDirs, ignoredDirs);
        List<IndexedNode> nodes = new ArrayList<>();
        for (Resource resource : resources) {
            IndexedNode node = buildNode(resource, vaultRoot);
            if (node != null) {
                nodes.add(node);
            }
        }
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("未能从目录中构建任何有效 Markdown 节点");
        }

        Map<String, IndexedNode> resolvedNodeByName = buildNodeLookup(nodes);
        Map<String, Set<String>> backlinks = buildBacklinks(nodes, resolvedNodeByName);
        List<Map<String, Object>> serializedNodes = new ArrayList<>();
        for (IndexedNode node : nodes) {
            serializedNodes.add(node.toSerializableMap(backlinks.getOrDefault(node.id(), Set.of())));
        }

        Path outputPath = resolveOutputPath(request, vaultRoot);
        writeIndex(outputPath, vaultRoot, serializedNodes);

        if (request.activate()) {
            properties.setIndexFilePath(outputPath.toString());
            ingestConfigService.saveLocalKnowledgeIndexPath(outputPath.toString());
        }

        return new IndexBuildResult(
                outputPath.toString(),
                vaultRoot.toString(),
                serializedNodes.size(),
                request.activate(),
                ignoredDirs,
                includeDirs,
                scope.scopePath()
        );
    }

    public IndexStatus currentStatus() {
        String configuredIndexPath = resolveConfiguredIndexPath();
        Map<String, String> ingestConfig = ingestConfigService.getConfig();
        String defaultVaultPath = firstConfiguredPath(ingestConfig.get("paths"));
        String defaultIgnoreDirs = ingestConfig.get("ignoreDirs");
        String scopePath = "";
        String scopeVaultRoot = "";
        List<String> scopeIncludes = List.of();
        List<String> scopeExcludes = List.of();
        try {
            Path defaultScopePath = defaultScopePath(defaultVaultPath);
            if (defaultScopePath != null && Files.exists(defaultScopePath) && Files.isRegularFile(defaultScopePath)) {
                LocalKnowledgeScopeService.LocalKnowledgeScope scope = localKnowledgeScopeService.load(defaultScopePath);
                scopePath = scope.scopePath();
                scopeVaultRoot = scope.vaultRoot();
                scopeIncludes = scope.includeDirs();
                scopeExcludes = scope.excludeDirs();
            }
        } catch (Exception ignored) {
            // 状态接口不因 scope 读取失败中断，错误继续由现有 error 字段承载索引层异常即可。
        }

        boolean exists = false;
        int nodeCount = 0;
        String buildId = "";
        String vaultRoot = "";
        String error = "";
        try {
            if (configuredIndexPath != null && !configuredIndexPath.isBlank()) {
                Path indexPath = Path.of(configuredIndexPath).toAbsolutePath().normalize();
                if (Files.exists(indexPath) && Files.isRegularFile(indexPath)) {
                    Map<?, ?> root = objectMapper.readValue(indexPath.toFile(), Map.class);
                    Object nodeValue = root.get("nodes");
                    if (nodeValue instanceof Collection<?> collection) {
                        nodeCount = collection.size();
                    }
                    buildId = valueAsString(root.get("buildId"));
                    vaultRoot = valueAsString(root.get("vaultRoot"));
                    exists = true;
                }
            }
        } catch (Exception e) {
            error = e.getMessage() == null ? "读取当前索引失败" : e.getMessage();
        }

        return new IndexStatus(
                properties.getDefaultMode().name(),
                configuredIndexPath == null ? "" : configuredIndexPath,
                exists,
                nodeCount,
                buildId,
                vaultRoot,
                properties.getOllamaModel(),
                defaultVaultPath,
                defaultIgnoreDirs == null ? "" : defaultIgnoreDirs,
                scopePath,
                scopeVaultRoot,
                scopeIncludes,
                scopeExcludes,
                error
        );
    }

    private void validateVaultRoot(Path vaultRoot) {
        if (!Files.exists(vaultRoot) || !Files.isDirectory(vaultRoot)) {
            throw new IllegalArgumentException("本地知识目录不存在或不是文件夹: " + vaultRoot);
        }
    }

    private IndexedNode buildNode(Resource resource, Path vaultRoot) {
        try {
            Path file = resource.getFile().toPath().toAbsolutePath().normalize();
            String markdown = Files.readString(file);
            if (markdown == null || markdown.isBlank()) {
                return null;
            }
            Path relative = vaultRoot.relativize(file);
            String relativePath = normalizeRelativePath(relative);
            ParsedFrontmatter frontmatter = parseFrontmatter(markdown);
            String content = stripFrontmatter(markdown);
            String title = resolveTitle(content, relativePath, frontmatter);
            String fileStem = removeMarkdownExtension(relative.getFileName() == null ? relativePath : relative.getFileName().toString());
            Set<String> aliases = new LinkedHashSet<>(frontmatter.aliases());
            if (!fileStem.isBlank() && !normalizeText(fileStem).equals(normalizeText(title))) {
                aliases.add(fileStem);
            }
            String summary = resolveSummary(content, frontmatter);
            Set<String> tags = new LinkedHashSet<>(frontmatter.tags());
            tags.addAll(extractTags(content));
            List<String> wikiLinks = extractWikiLinks(content);
            String id = normalizeId(relativePath);

            return new IndexedNode(
                    id,
                    title,
                    List.copyOf(aliases),
                    summary,
                    List.copyOf(tags),
                    relativePath,
                    wikiLinks
            );
        } catch (IOException e) {
            throw new IllegalStateException("读取 Markdown 文件失败: " + resource.getFilename(), e);
        }
    }

    private Map<String, IndexedNode> buildNodeLookup(List<IndexedNode> nodes) {
        Map<String, IndexedNode> lookup = new LinkedHashMap<>();
        for (IndexedNode node : nodes) {
            registerLookupKey(lookup, node.title(), node);
            registerLookupKey(lookup, removeMarkdownExtension(lastPathSegment(node.filePath())), node);
            for (String alias : node.aliases()) {
                registerLookupKey(lookup, alias, node);
            }
        }
        return lookup;
    }

    private void registerLookupKey(Map<String, IndexedNode> lookup, String key, IndexedNode node) {
        String normalized = normalizeText(key);
        if (!normalized.isBlank() && !lookup.containsKey(normalized)) {
            lookup.put(normalized, node);
        }
    }

    private Map<String, Set<String>> buildBacklinks(List<IndexedNode> nodes, Map<String, IndexedNode> nodeLookup) {
        Map<String, Set<String>> backlinks = new LinkedHashMap<>();
        for (IndexedNode node : nodes) {
            backlinks.put(node.id(), new LinkedHashSet<>());
        }
        for (IndexedNode source : nodes) {
            for (String wikiLink : source.wikiLinks()) {
                IndexedNode target = nodeLookup.get(normalizeText(wikiLink));
                if (target == null || Objects.equals(target.id(), source.id())) {
                    continue;
                }
                backlinks.computeIfAbsent(target.id(), ignored -> new LinkedHashSet<>()).add(source.title());
            }
        }
        return backlinks;
    }

    private Path resolveOutputPath(IndexBuildRequest request, Path vaultRoot) {
        if (request.outputPath() != null && !request.outputPath().isBlank()) {
            return Path.of(request.outputPath()).toAbsolutePath().normalize();
        }
        String configuredIndexPath = resolveConfiguredIndexPath();
        if (configuredIndexPath != null && !configuredIndexPath.isBlank()) {
            return Path.of(configuredIndexPath).toAbsolutePath().normalize();
        }
        String folderName = sanitizeFileName(vaultRoot.getFileName() == null ? "vault" : vaultRoot.getFileName().toString());
        return Path.of("data", "knowledge-index", folderName + "-local-index.json").toAbsolutePath().normalize();
    }

    private String resolveConfiguredIndexPath() {
        String configured = properties.getIndexFilePath();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        String persisted = ingestConfigService.getLocalKnowledgeIndexPath();
        if (persisted != null && !persisted.isBlank()) {
            properties.setIndexFilePath(persisted);
            return persisted;
        }
        return configured;
    }

    private void writeIndex(Path outputPath, Path vaultRoot, List<Map<String, Object>> nodes) {
        try {
            Path parent = outputPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("schemaVersion", properties.getRequiredSchemaVersion());
            root.put("buildId", UUID.randomUUID().toString());
            root.put("generatedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
            root.put("vaultRoot", vaultRoot.toString());
            root.put("nodeCount", nodes.size());
            root.put("nodes", nodes);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), root);
        } catch (IOException e) {
            throw new IllegalStateException("写入本地知识索引失败: " + outputPath, e);
        }
    }

    private BuildScope resolveBuildScope(IndexBuildRequest request) {
        if (request.scopeFilePath() != null && !request.scopeFilePath().isBlank()) {
            LocalKnowledgeScopeService.LocalKnowledgeScope scope = localKnowledgeScopeService.load(
                    Path.of(request.scopeFilePath()).toAbsolutePath().normalize()
            );
            List<String> includeDirs = sanitizeList(scope.includeDirs());
            List<String> ignoredDirs = mergeLists(scope.defaultIgnoreDirNames(), scope.excludeDirs(), request.ignoreDirs());
            return new BuildScope(
                    scope.scopePath(),
                    scope.vaultRoot(),
                    includeDirs,
                    ignoredDirs
            );
        }
        String scopePath = request.scopeFilePath() == null ? "" : request.scopeFilePath().trim();
        String vaultPath = resolveVaultPath(request);
        List<String> includeDirs = sanitizeList(request.includeDirs());
        List<String> ignoredDirs = resolveIgnoredDirs(request);
        return new BuildScope(scopePath, vaultPath, includeDirs, ignoredDirs);
    }

    private String resolveVaultPath(IndexBuildRequest request) {
        if (request.vaultPath() != null && !request.vaultPath().isBlank()) {
            return request.vaultPath().trim();
        }
        String configured = firstConfiguredPath(ingestConfigService.getConfig().get("paths"));
        if (configured.isBlank()) {
            throw new IllegalArgumentException("未提供 vaultPath，且 ingest config 中也没有可用路径");
        }
        return configured;
    }

    private List<String> resolveIgnoredDirs(IndexBuildRequest request) {
        if (request.excludeDirs() != null && !request.excludeDirs().isEmpty()) {
            return mergeLists(request.ignoreDirs(), request.excludeDirs());
        }
        if (request.ignoreDirs() != null && !request.ignoreDirs().isEmpty()) {
            return sanitizeList(request.ignoreDirs());
        }
        String configured = ingestConfigService.getConfig().get("ignoreDirs");
        if (configured == null || configured.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String part : configured.split(",")) {
            String normalized = part == null ? "" : part.trim();
            if (!normalized.isBlank()) {
                values.add(normalized);
            }
        }
        return List.copyOf(values);
    }

    private List<String> mergeLists(List<String>... values) {
        List<String> result = new ArrayList<>();
        if (values == null) {
            return List.of();
        }
        for (List<String> items : values) {
            for (String item : sanitizeList(items)) {
                if (!result.contains(item)) {
                    result.add(item);
                }
            }
        }
        return List.copyOf(result);
    }

    private List<String> sanitizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            String normalized = value == null ? "" : value.trim();
            if (!normalized.isBlank() && !result.contains(normalized)) {
                result.add(normalized);
            }
        }
        return List.copyOf(result);
    }

    private String firstConfiguredPath(String paths) {
        if (paths == null || paths.isBlank()) {
            return "";
        }
        String[] lines = paths.split("\\R");
        for (String line : lines) {
            String normalized = line == null ? "" : line.trim();
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    private Path defaultScopePath(String vaultPath) {
        if (vaultPath == null || vaultPath.isBlank()) {
            return null;
        }
        return Path.of(vaultPath).toAbsolutePath().normalize().resolve("meta").resolve("local-knowledge-scope.yaml");
    }

    private ParsedFrontmatter parseFrontmatter(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return ParsedFrontmatter.empty();
        }
        String[] lines = markdown.split("\\R", -1);
        if (lines.length < 3 || !FRONTMATTER_BOUNDARY.matcher(lines[0].trim()).matches()) {
            return ParsedFrontmatter.empty();
        }
        Map<String, List<String>> listValues = new LinkedHashMap<>();
        Map<String, String> scalarValues = new LinkedHashMap<>();
        String currentListKey = null;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (FRONTMATTER_BOUNDARY.matcher(line.trim()).matches()) {
                break;
            }
            String trimmed = line.trim();
            if (trimmed.startsWith("- ") && currentListKey != null) {
                listValues.computeIfAbsent(currentListKey, ignored -> new ArrayList<>())
                        .add(cleanYamlValue(trimmed.substring(2)));
                continue;
            }
            currentListKey = null;
            int colonIndex = trimmed.indexOf(':');
            if (colonIndex <= 0) {
                continue;
            }
            String key = trimmed.substring(0, colonIndex).trim().toLowerCase(Locale.ROOT);
            String rawValue = trimmed.substring(colonIndex + 1).trim();
            if (rawValue.startsWith("[") && rawValue.endsWith("]")) {
                List<String> items = new ArrayList<>();
                for (String part : rawValue.substring(1, rawValue.length() - 1).split(",")) {
                    String cleaned = cleanYamlValue(part);
                    if (!cleaned.isBlank()) {
                        items.add(cleaned);
                    }
                }
                listValues.put(key, items);
                continue;
            }
            if (rawValue.isBlank()) {
                currentListKey = key;
                listValues.computeIfAbsent(key, ignored -> new ArrayList<>());
                continue;
            }
            scalarValues.put(key, cleanYamlValue(rawValue));
        }
        Set<String> aliases = new LinkedHashSet<>(listValues.getOrDefault("aliases", List.of()));
        if (scalarValues.containsKey("alias")) {
            aliases.add(scalarValues.get("alias"));
        }
        Set<String> tags = new LinkedHashSet<>(listValues.getOrDefault("tags", List.of()));
        if (scalarValues.containsKey("tags")) {
            for (String part : scalarValues.get("tags").split(",")) {
                String cleaned = cleanYamlValue(part);
                if (!cleaned.isBlank()) {
                    tags.add(cleaned);
                }
            }
        }
        return new ParsedFrontmatter(
                scalarValues.getOrDefault("title", ""),
                scalarValues.getOrDefault("summary", scalarValues.getOrDefault("description", "")),
                List.copyOf(aliases),
                List.copyOf(tags)
        );
    }

    private String stripFrontmatter(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        String[] lines = markdown.split("\\R", -1);
        if (lines.length < 3 || !FRONTMATTER_BOUNDARY.matcher(lines[0].trim()).matches()) {
            return markdown;
        }
        for (int i = 1; i < lines.length; i++) {
            if (FRONTMATTER_BOUNDARY.matcher(lines[i].trim()).matches()) {
                StringBuilder builder = new StringBuilder();
                for (int j = i + 1; j < lines.length; j++) {
                    builder.append(lines[j]);
                    if (j < lines.length - 1) {
                        builder.append(System.lineSeparator());
                    }
                }
                return builder.toString();
            }
        }
        return markdown;
    }

    private String resolveTitle(String content, String relativePath, ParsedFrontmatter frontmatter) {
        if (frontmatter.title() != null && !frontmatter.title().isBlank()) {
            return frontmatter.title().trim();
        }
        for (String rawLine : content.split("\\R")) {
            Matcher matcher = HEADING_PATTERN.matcher(rawLine.trim());
            if (matcher.find()) {
                String title = matcher.group(1).trim();
                if (!title.isBlank()) {
                    return title;
                }
            }
        }
        return removeMarkdownExtension(lastPathSegment(relativePath));
    }

    private String resolveSummary(String content, ParsedFrontmatter frontmatter) {
        if (frontmatter.summary() != null && !frontmatter.summary().isBlank()) {
            return trimToLength(frontmatter.summary().trim(), 240);
        }
        StringBuilder builder = new StringBuilder();
        for (String rawLine : content.split("\\R")) {
            String line = rawLine.trim();
            if (line.isBlank() || line.startsWith("#") || line.startsWith("```") || line.startsWith("-") || line.startsWith("*")) {
                continue;
            }
            line = line.replaceAll("\\!\\[[^\\]]*\\]\\([^)]*\\)", "")
                    .replaceAll("\\[\\[([^\\]|#]+)(?:#[^\\]|]+)?(?:\\|.*?)?\\]\\]", "$1")
                    .replaceAll("`", "")
                    .trim();
            if (line.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(line);
            if (builder.length() >= 240) {
                break;
            }
        }
        if (builder.length() == 0) {
            return "无摘要";
        }
        return trimToLength(builder.toString(), 240);
    }

    private Set<String> extractTags(String content) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = TAG_PATTERN.matcher(content);
        while (matcher.find()) {
            String tag = matcher.group(2) == null ? "" : matcher.group(2).trim();
            if (!tag.isBlank()) {
                result.add(tag);
            }
        }
        return result;
    }

    private List<String> extractWikiLinks(String content) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = WIKI_LINK_PATTERN.matcher(content);
        while (matcher.find()) {
            String link = matcher.group(1) == null ? "" : matcher.group(1).trim();
            if (!link.isBlank()) {
                result.add(link);
            }
        }
        return List.copyOf(result);
    }

    private String normalizeRelativePath(Path relative) {
        return relative.toString().replace("\\", "/");
    }

    private String normalizeId(String relativePath) {
        String withoutExtension = removeMarkdownExtension(relativePath);
        return withoutExtension.replace("\\", "/");
    }

    private String removeMarkdownExtension(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).endsWith(".md")
                ? value.substring(0, value.length() - 3)
                : value;
    }

    private String lastPathSegment(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int index = value.replace("\\", "/").lastIndexOf('/');
        return index >= 0 ? value.substring(index + 1) : value;
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String cleanYamlValue(String value) {
        String normalized = value == null ? "" : value.trim();
        if ((normalized.startsWith("\"") && normalized.endsWith("\""))
                || (normalized.startsWith("'") && normalized.endsWith("'"))) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        return normalized;
    }

    private String trimToLength(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, limit));
    }

    private String sanitizeFileName(String value) {
        String normalized = value == null ? "vault" : value.trim();
        if (normalized.isBlank()) {
            return "vault";
        }
        return normalized.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String valueAsString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public record IndexBuildRequest(String vaultPath,
                                    String outputPath,
                                    List<String> ignoreDirs,
                                    List<String> includeDirs,
                                    List<String> excludeDirs,
                                    String scopeFilePath,
                                    boolean activate) {
    }

    public record IndexBuildResult(String outputPath,
                                   String vaultPath,
                                   int nodeCount,
                                   boolean activated,
                                   List<String> ignoreDirs,
                                   List<String> includeDirs,
                                   String scopeFilePath) {
    }

    public record IndexStatus(String defaultMode,
                              String indexFilePath,
                              boolean indexExists,
                              int nodeCount,
                              String buildId,
                              String vaultRoot,
                              String ollamaModel,
                              String configuredVaultPath,
                              String configuredIgnoreDirs,
                              String scopeFilePath,
                              String scopeVaultRoot,
                              List<String> scopeIncludeDirs,
                              List<String> scopeExcludeDirs,
                              String error) {
    }

    private record BuildScope(String scopePath, String vaultPath, List<String> includeDirs, List<String> ignoredDirs) {
    }

    private record ParsedFrontmatter(String title, String summary, List<String> aliases, List<String> tags) {
        private static ParsedFrontmatter empty() {
            return new ParsedFrontmatter("", "", List.of(), List.of());
        }
    }

    private record IndexedNode(String id,
                               String title,
                               List<String> aliases,
                               String summary,
                               List<String> tags,
                               String filePath,
                               List<String> wikiLinks) {

        private Map<String, Object> toSerializableMap(Set<String> backlinks) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            result.put("title", title);
            result.put("aliases", aliases);
            result.put("summary", summary);
            result.put("tags", tags);
            result.put("filePath", filePath);
            result.put("wikiLinks", wikiLinks);
            result.put("backlinks", List.copyOf(backlinks));
            return result;
        }
    }
}








