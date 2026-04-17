package io.github.imzmq.interview.ingestion.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.imzmq.interview.entity.ingestion.SyncIndexDO;
import io.github.imzmq.interview.knowledge.application.indexing.LexicalIndexService;
import io.github.imzmq.interview.knowledge.application.indexing.ParentChildIndexService;
import io.github.imzmq.interview.mapper.ingestion.SyncIndexMapper;
import io.github.imzmq.interview.media.application.ImageService;
import io.github.imzmq.interview.rag.core.DocumentSplitter;
import io.github.imzmq.interview.rag.core.NoteLoader;
import io.github.imzmq.interview.rag.core.ObsidianKnowledgeExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.util.StreamUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class IngestionService {

    private static final Logger logger = LoggerFactory.getLogger(IngestionService.class);
    private static final Path BROWSER_ASSET_ROOT = Path.of("uploads", "browser-assets");

    private final NoteLoader noteLoader;
    private final DocumentSplitter documentSplitter;
    private final ObsidianKnowledgeExtractor knowledgeExtractor;
    private final LexicalIndexService lexicalIndexService;
    private final VectorStore vectorStore;
    private final SyncIndexMapper syncIndexMapper;
    private final ParentChildIndexService parentChildIndexService;
    private final ImageService imageService;
    // 注入图谱持久化组件
    private final io.github.imzmq.interview.graph.domain.TechConceptRepository techConceptRepository;

    private volatile SyncSummary lastSummary = new SyncSummary(0, 0, 0, 0, 0, 0, 0);
    private volatile long lastSyncTime = 0L;

    public IngestionService(NoteLoader noteLoader, DocumentSplitter documentSplitter, ObsidianKnowledgeExtractor knowledgeExtractor, LexicalIndexService lexicalIndexService, VectorStore vectorStore, SyncIndexMapper syncIndexMapper, ParentChildIndexService parentChildIndexService, ImageService imageService, io.github.imzmq.interview.graph.domain.TechConceptRepository techConceptRepository) {
        this.noteLoader = noteLoader;
        this.documentSplitter = documentSplitter;
        this.knowledgeExtractor = knowledgeExtractor;
        this.lexicalIndexService = lexicalIndexService;
        this.vectorStore = vectorStore;
        this.syncIndexMapper = syncIndexMapper;
        this.parentChildIndexService = parentChildIndexService;
        this.imageService = imageService;
        this.techConceptRepository = techConceptRepository;
    }

    public SyncSummary sync(String vaultPath) {
        return sync(vaultPath, null);
    }

    public SyncSummary sync(String vaultPath, List<String> ignoredDirs) {
        logger.info("Starting sync for vault: {}", vaultPath);
        List<Resource> resources = noteLoader.loadNotes(vaultPath, ignoredDirs);
        String normalizedVaultPath = normalizeLocalPath(vaultPath);
        Set<String> currentFiles = new HashSet<>();
        int newFileCount = 0;
        int modifiedFileCount = 0;
        int unchangedFileCount = 0;
        int failedFileCount = 0;
        int skippedEmptyFileCount = 0;

        for (Resource resource : resources) {
            try {
                String filePath = resource.getFile().getAbsolutePath();
                currentFiles.add(filePath);
                String currentHash = calculateHash(resource);

                SyncIndexDO existingIndex = syncIndexMapper.selectOne(
                        new LambdaQueryWrapper<SyncIndexDO>().eq(SyncIndexDO::getFilePath, filePath));

                if (existingIndex != null) {
                    if (!existingIndex.getFileHash().equals(currentHash)) {
                        logger.info("File modified: {}", filePath);
                        AddStatus status = updateFile(resource, filePath, currentHash, existingIndex.getDocIds());
                        if (status == AddStatus.ADDED) {
                            modifiedFileCount++;
                        } else if (status == AddStatus.SKIPPED_EMPTY) {
                            skippedEmptyFileCount++;
                        } else {
                            failedFileCount++;
                        }
                    } else {
                        logger.debug("File unchanged: {}", filePath);
                        unchangedFileCount++;
                    }
                } else {
                    logger.info("New file detected: {}", filePath);
                    AddStatus status = addFile(resource, filePath, currentHash);
                    if (status == AddStatus.ADDED) {
                        newFileCount++;
                    } else if (status == AddStatus.SKIPPED_EMPTY) {
                        skippedEmptyFileCount++;
                    } else {
                        failedFileCount++;
                    }
                }

            } catch (IOException e) {
                logger.error("Error processing file: {}", resource.getFilename(), e);
                failedFileCount++;
            }
        }

        // Handle deletions
        List<SyncIndexDO> allIndexes = syncIndexMapper.selectList(null);
        List<SyncIndexDO> deletedFiles = allIndexes.stream()
                .filter(idx -> !currentFiles.contains(idx.getFilePath()))
                .filter(idx -> !idx.getFilePath().startsWith("browser://"))
                .filter(idx -> belongsToVault(idx.getFilePath(), normalizedVaultPath))
                .collect(Collectors.toList());

        for (SyncIndexDO deletedIndex : deletedFiles) {
            logger.info("File deleted: {}", deletedIndex.getFilePath());
            removeFile(deletedIndex.getFilePath(), deletedIndex.getDocIds());
        }

        SyncSummary summary = new SyncSummary(
                resources.size(),
                newFileCount,
                modifiedFileCount,
                unchangedFileCount,
                deletedFiles.size(),
                failedFileCount,
                skippedEmptyFileCount
        );
        lastSummary = summary;
        lastSyncTime = System.currentTimeMillis();
        logger.info("Sync complete. {}", summary.toLogMessage());
        return summary;
    }

    public SyncSummary forceReindexParentChild(String vaultPath, List<String> ignoredDirs) {
        logger.info("Starting force reindex for vault: {}", vaultPath);
        List<Resource> resources = noteLoader.loadNotes(vaultPath, ignoredDirs);
        String normalizedVaultPath = normalizeLocalPath(vaultPath);
        Set<String> currentFiles = new HashSet<>();
        int newFileCount = 0;
        int modifiedFileCount = 0;
        int unchangedFileCount = 0;
        int failedFileCount = 0;
        int skippedEmptyFileCount = 0;

        for (Resource resource : resources) {
            try {
                String filePath = resource.getFile().getAbsolutePath();
                currentFiles.add(filePath);
                String currentHash = calculateHash(resource);
                SyncIndexDO existingIndex = syncIndexMapper.selectOne(
                        new LambdaQueryWrapper<SyncIndexDO>().eq(SyncIndexDO::getFilePath, filePath));
                if (existingIndex != null) {
                    AddStatus status = updateFile(resource, filePath, currentHash, existingIndex.getDocIds());
                    if (status == AddStatus.ADDED) {
                        modifiedFileCount++;
                    } else if (status == AddStatus.SKIPPED_EMPTY) {
                        skippedEmptyFileCount++;
                    } else {
                        failedFileCount++;
                    }
                } else {
                    AddStatus status = addFile(resource, filePath, currentHash);
                    if (status == AddStatus.ADDED) {
                        newFileCount++;
                    } else if (status == AddStatus.SKIPPED_EMPTY) {
                        skippedEmptyFileCount++;
                    } else {
                        failedFileCount++;
                    }
                }
            } catch (IOException e) {
                logger.error("Error processing file during force reindex: {}", resource.getFilename(), e);
                failedFileCount++;
            }
        }

        List<SyncIndexDO> allIndexes = syncIndexMapper.selectList(null);
        List<SyncIndexDO> deletedFiles = allIndexes.stream()
                .filter(idx -> !currentFiles.contains(idx.getFilePath()))
                .filter(idx -> !idx.getFilePath().startsWith("browser://"))
                .filter(idx -> belongsToVault(idx.getFilePath(), normalizedVaultPath))
                .collect(Collectors.toList());
        for (SyncIndexDO deletedIndex : deletedFiles) {
            removeFile(deletedIndex.getFilePath(), deletedIndex.getDocIds());
        }

        SyncSummary summary = new SyncSummary(
                resources.size(),
                newFileCount,
                modifiedFileCount,
                unchangedFileCount,
                deletedFiles.size(),
                failedFileCount,
                skippedEmptyFileCount
        );
        lastSummary = summary;
        lastSyncTime = System.currentTimeMillis();
        logger.info("Force reindex complete. {}", summary.toLogMessage());
        return summary;
    }

    public SyncSummary syncUploadedNotes(List<MultipartFile> files, List<String> relativePaths, String folderName, List<String> ignoredDirs) {
        if (files == null || files.isEmpty()) {
            return new SyncSummary(0, 0, 0, 0, 0, 0, 0);
        }
        String folderKey = sanitizeFolderKey(folderName);
        persistUploadedAssets(files, relativePaths, folderKey);
        String prefix = "browser://" + folderKey + "/";
        Set<String> ignoredSet = ignoredDirs == null ? Set.of() : ignoredDirs.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .collect(Collectors.toSet());
        Set<String> currentFiles = new HashSet<>();
        int newFileCount = 0;
        int modifiedFileCount = 0;
        int unchangedFileCount = 0;
        int failedFileCount = 0;
        int skippedEmptyFileCount = 0;

        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            String relativePath = relativePaths != null && i < relativePaths.size() ? relativePaths.get(i) : file.getOriginalFilename();
            if (relativePath == null || relativePath.isBlank()) {
                relativePath = file.getOriginalFilename();
            }
            if (relativePath == null || !relativePath.toLowerCase().endsWith(".md")) {
                continue;
            }
            String normalizedRelativePath = relativePath.replace("\\", "/");
            if (shouldIgnore(normalizedRelativePath, ignoredSet)) {
                continue;
            }
            String filePath = prefix + normalizedRelativePath;
            currentFiles.add(filePath);
            try {
                String currentHash = calculateHash(file);
                SyncIndexDO existingIndex = syncIndexMapper.selectOne(
                        new LambdaQueryWrapper<SyncIndexDO>().eq(SyncIndexDO::getFilePath, filePath));

                if (existingIndex != null) {
                    if (!existingIndex.getFileHash().equals(currentHash)) {
                        AddStatus status = updateFile(file, filePath, currentHash, existingIndex.getDocIds());
                        if (status == AddStatus.ADDED) {
                            modifiedFileCount++;
                        } else if (status == AddStatus.SKIPPED_EMPTY) {
                            skippedEmptyFileCount++;
                        } else {
                            failedFileCount++;
                        }
                    } else {
                        unchangedFileCount++;
                    }
                } else {
                    AddStatus status = addFile(file, filePath, currentHash);
                    if (status == AddStatus.ADDED) {
                        newFileCount++;
                    } else if (status == AddStatus.SKIPPED_EMPTY) {
                        skippedEmptyFileCount++;
                    } else {
                        failedFileCount++;
                    }
                }
            } catch (IOException e) {
                logger.error("Error processing uploaded file: {}", relativePath, e);
                failedFileCount++;
            }
        }

        List<SyncIndexDO> allIndexes = syncIndexMapper.selectList(
                new LambdaQueryWrapper<SyncIndexDO>().likeRight(SyncIndexDO::getFilePath, prefix));

        List<SyncIndexDO> deletedFiles = allIndexes.stream()
                .filter(idx -> !currentFiles.contains(idx.getFilePath()))
                .collect(Collectors.toList());

        for (SyncIndexDO deletedIndex : deletedFiles) {
            removeFile(deletedIndex.getFilePath(), deletedIndex.getDocIds());
        }

        SyncSummary summary = new SyncSummary(
                currentFiles.size(),
                newFileCount,
                modifiedFileCount,
                unchangedFileCount,
                deletedFiles.size(),
                failedFileCount,
                skippedEmptyFileCount
        );
        lastSummary = summary;
        lastSyncTime = System.currentTimeMillis();
        return summary;
    }

    public Map<String, Object> getStats() {
        Map<String, Object> result = new LinkedHashMap<>();
        long totalIndexed = syncIndexMapper.selectCount(null);
        int totalScanned = lastSummary.totalScanned > 0 ? lastSummary.totalScanned : Math.toIntExact(Math.min(Integer.MAX_VALUE, totalIndexed));
        result.put("totalScanned", totalScanned);
        result.put("totalIndexed", totalIndexed);
        result.put("failedFiles", lastSummary.failedFiles);
        int denominator = Math.max(1, totalScanned);
        int success = Math.max(0, totalScanned - lastSummary.failedFiles);
        int successRate = Math.max(0, Math.min(100, (success * 100) / denominator));
        result.put("successRate", successRate + "%");
        result.put("lastSyncTime", lastSyncTime == 0L ? null : lastSyncTime);
        result.put("storagePercent", Math.min(100, totalIndexed));
        result.put("recentReports", List.of());
        return result;
    }

    public boolean deleteByFilePath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return false;
        }
        SyncIndexDO existing = syncIndexMapper.selectOne(
                new LambdaQueryWrapper<SyncIndexDO>().eq(SyncIndexDO::getFilePath, filePath).last("LIMIT 1")
        );
        if (existing == null) {
            return false;
        }
        removeFile(filePath, existing.getDocIds());
        return true;
    }

    public boolean rechunkByFilePath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return false;
        }
        if (filePath.startsWith("browser://")) {
            return false;
        }
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            return false;
        }
        SyncIndexDO existing = syncIndexMapper.selectOne(
                new LambdaQueryWrapper<SyncIndexDO>().eq(SyncIndexDO::getFilePath, filePath).last("LIMIT 1")
        );
        try {
            Resource resource = new FileSystemResource(file);
            String hash = calculateHash(resource);
            AddStatus status = updateFile(resource, filePath, hash, existing == null ? List.of() : existing.getDocIds());
            return status == AddStatus.ADDED || status == AddStatus.SKIPPED_EMPTY;
        } catch (Exception e) {
            logger.warn("Rechunk failed for filePath={}", filePath, e);
            return false;
        }
    }

    public Map<String, Object> getParentChildReport() {
        Map<String, Object> report = new LinkedHashMap<>();
        long parentCount = parentChildIndexService.countParents();
        long childCount = parentChildIndexService.countChildren();
        report.put("parentCount", parentCount);
        report.put("childCount", childCount);
        report.put("avgChildrenPerParent", parentCount == 0 ? 0.0 : ((double) childCount) / parentCount);
        report.put("lastSyncTime", lastSyncTime == 0L ? null : lastSyncTime);
        report.put("lastSummary", Map.of(
                "totalScanned", lastSummary.totalScanned,
                "newFiles", lastSummary.newFiles,
                "modifiedFiles", lastSummary.modifiedFiles,
                "unchangedFiles", lastSummary.unchangedFiles,
                "deletedFiles", lastSummary.deletedFiles,
                "failedFiles", lastSummary.failedFiles,
                "skippedEmptyFiles", lastSummary.skippedEmptyFiles
        ));
        return report;
    }

    private String sanitizeFolderKey(String folderName) {
        String raw = folderName == null || folderName.isBlank() ? "selected-folder" : folderName.trim();
        String sanitized = raw.replaceAll("[^a-zA-Z0-9._-]", "_");
        return sanitized.isBlank() ? "selected-folder" : sanitized;
    }

    private boolean shouldIgnore(String relativePath, Set<String> ignoredDirs) {
        if (ignoredDirs == null || ignoredDirs.isEmpty()) {
            return false;
        }
        String[] parts = relativePath.split("/");
        for (String part : parts) {
            if (ignoredDirs.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private void persistUploadedAssets(List<MultipartFile> files, List<String> relativePaths, String folderKey) {
        if (files == null || files.isEmpty()) {
            return;
        }
        Path baseDir = BROWSER_ASSET_ROOT.resolve(folderKey).toAbsolutePath().normalize();
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            String relativePath = relativePaths != null && i < relativePaths.size() ? relativePaths.get(i) : file.getOriginalFilename();
            if (relativePath == null || relativePath.isBlank()) {
                relativePath = file.getOriginalFilename();
            }
            if (relativePath == null || !isSupportedImage(relativePath)) {
                continue;
            }
            try {
                Path target = baseDir.resolve(relativePath.replace("\\", "/")).normalize();
                if (!target.startsWith(baseDir)) {
                    logger.warn("Skip uploaded image outside asset root: {}", relativePath);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (InputStream inputStream = file.getInputStream()) {
                    Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                logger.warn("Persist uploaded image asset failed: {}", relativePath, e);
            }
        }
    }

    private boolean isSupportedImage(String path) {
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png")
                || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".gif")
                || lower.endsWith(".webp");
    }

    private AddStatus addFile(Resource resource, String filePath, String hash) {
        try {
            String content = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            if (content == null || content.trim().isEmpty()) {
                logger.info("Skip empty file: {}", filePath);
                return AddStatus.SKIPPED_EMPTY;
            }
            List<Document> documents = splitIntoKnowledgeDocuments(content, filePath);
            if (documents.isEmpty()) {
                logger.info("Skip file without valid knowledge blocks: {}", filePath);
                return AddStatus.SKIPPED_EMPTY;
            }
            vectorStore.add(documents);
            lexicalIndexService.upsertDocuments(documents);
            parentChildIndexService.rebuildByChunks(filePath, documents);
            imageService.indexImagesForDocument(filePath, content, documents);
            
            // GraphRAG: 提取双向链接并写入 Neo4j
            processGraphRelationships(documents);
            
            List<String> docIds = documents.stream().map(Document::getId).collect(Collectors.toList());
            saveToIndexDb(filePath, hash, docIds);
            return AddStatus.ADDED;
        } catch (Exception e) {
            logger.error("Failed to add file to vector store: {}", filePath, e);
            return AddStatus.FAILED;
        }
    }

    private AddStatus updateFile(Resource resource, String filePath, String hash, List<String> oldDocIds) {
        removeFile(filePath, oldDocIds); // Delete old vectors
        return addFile(resource, filePath, hash); // Add new vectors
    }

    private AddStatus addFile(MultipartFile file, String filePath, String hash) {
        try {
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            if (content.trim().isEmpty()) {
                return AddStatus.SKIPPED_EMPTY;
            }
            List<Document> documents = splitIntoKnowledgeDocuments(content, filePath);
            if (documents.isEmpty()) {
                return AddStatus.SKIPPED_EMPTY;
            }
            vectorStore.add(documents);
            lexicalIndexService.upsertDocuments(documents);
            parentChildIndexService.rebuildByChunks(filePath, documents);
            imageService.indexImagesForDocument(filePath, content, documents);
            
            // GraphRAG: 提取双向链接并写入 Neo4j
            processGraphRelationships(documents);
            
            List<String> docIds = documents.stream().map(Document::getId).collect(Collectors.toList());
            saveToIndexDb(filePath, hash, docIds);
            return AddStatus.ADDED;
        } catch (Exception e) {
            logger.error("Failed to add uploaded file to vector store: {}", filePath, e);
            return AddStatus.FAILED;
        }
    }

    private AddStatus updateFile(MultipartFile file, String filePath, String hash, List<String> oldDocIds) {
        removeFile(filePath, oldDocIds);
        return addFile(file, filePath, hash);
    }

    private void saveToIndexDb(String filePath, String hash, List<String> docIds) {
        SyncIndexDO existing = syncIndexMapper.selectOne(
                new LambdaQueryWrapper<SyncIndexDO>().eq(SyncIndexDO::getFilePath, filePath));
        if (existing == null) {
            SyncIndexDO newIndex = new SyncIndexDO();
            newIndex.setFilePath(filePath);
            newIndex.setFileHash(hash);
            newIndex.setDocIds(docIds);
            newIndex.setCreatedAt(LocalDateTime.now());
            syncIndexMapper.insert(newIndex);
        } else {
            existing.setFileHash(hash);
            existing.setDocIds(docIds);
            syncIndexMapper.updateById(existing);
        }
    }

    private List<Document> splitIntoKnowledgeDocuments(String content, String filePath) {
        String enrichedContent = imageService.enrichMarkdownWithImageSummaries(content, filePath);
        ObsidianKnowledgeExtractor.ExtractionResult extractionResult = knowledgeExtractor.extract(enrichedContent, filePath);
        if (extractionResult.isEmpty()) {
            return List.of();
        }
        List<Document> chunks = documentSplitter.split(extractionResult.documents());
        return chunks.stream()
                .filter(doc -> doc.getText() != null && !doc.getText().trim().isEmpty())
                .peek(doc -> {
                    doc.getMetadata().putIfAbsent("file_path", filePath);
                    doc.getMetadata().putIfAbsent("source_type", "obsidian");
                    Object knowledgeTags = doc.getMetadata().get("knowledge_tags");
                    if (knowledgeTags == null || knowledgeTags.toString().isBlank()) {
                        doc.getMetadata().put("knowledge_tags", "");
                    }
                    // 保留 wiki_links 元数据
                    Object wikiLinks = doc.getMetadata().get("wiki_links");
                    if (wikiLinks == null) {
                        doc.getMetadata().put("wiki_links", "");
                    }
                })
                .collect(Collectors.toList());
    }

    private void processGraphRelationships(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        try {
            for (Document doc : documents) {
                String filePath = (String) doc.getMetadata().get("file_path");
                if (filePath == null) continue;

                String fileName = new File(filePath).getName();
                int dotIndex = fileName.lastIndexOf('.');
                String mainConceptName = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;

                io.github.imzmq.interview.graph.domain.TechConcept mainConcept = techConceptRepository.findById(mainConceptName)
                        .orElse(new io.github.imzmq.interview.graph.domain.TechConcept(mainConceptName));
                
                if (mainConcept.getDescription() == null) {
                    mainConcept.setDescription(doc.getText().length() > 200 ? doc.getText().substring(0, 200) + "..." : doc.getText());
                }

                Object wikiLinksObj = doc.getMetadata().get("wiki_links");
                if (wikiLinksObj != null && !wikiLinksObj.toString().isBlank()) {
                    String[] links = wikiLinksObj.toString().split(",");
                    for (String link : links) {
                        String relatedName = link.trim();
                        if (!relatedName.isBlank() && !relatedName.equals(mainConceptName)) {
                            io.github.imzmq.interview.graph.domain.TechConcept relatedConcept = techConceptRepository.findById(relatedName)
                                    .orElse(new io.github.imzmq.interview.graph.domain.TechConcept(relatedName));
                            mainConcept.addRelatedConcept(relatedConcept);
                            techConceptRepository.save(relatedConcept);
                        }
                    }
                }
                techConceptRepository.save(mainConcept);
            }
            logger.info("GraphRAG: 成功处理并同步了文档的图谱关系至 Neo4j");
        } catch (Exception e) {
            logger.error("GraphRAG: 同步图谱关系失败", e);
        }
    }

    private void removeFile(String filePath, List<String> docIds) {
        if (docIds != null && !docIds.isEmpty()) {
            vectorStore.delete(docIds);
            lexicalIndexService.removeByIds(docIds);
        }
        parentChildIndexService.deleteByFilePath(filePath);
        syncIndexMapper.delete(new LambdaQueryWrapper<SyncIndexDO>().eq(SyncIndexDO::getFilePath, filePath));
    }

    private String calculateHash(Resource resource) throws IOException {
        try (InputStream is = resource.getInputStream()) {
            return DigestUtils.md5DigestAsHex(is);
        }
    }

    private String calculateHash(MultipartFile file) throws IOException {
        try (InputStream is = file.getInputStream()) {
            return DigestUtils.md5DigestAsHex(is);
        }
    }

    private String normalizeLocalPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            return new File(path).getCanonicalPath();
        } catch (IOException e) {
            return new File(path).getAbsolutePath();
        }
    }

    private boolean belongsToVault(String filePath, String normalizedVaultPath) {
        if (filePath == null || filePath.isBlank() || normalizedVaultPath == null || normalizedVaultPath.isBlank()) {
            return false;
        }
        String normalizedFilePath = normalizeLocalPath(filePath);
        if (normalizedFilePath == null) {
            return false;
        }
        if (normalizedFilePath.equals(normalizedVaultPath)) {
            return true;
        }
        String prefix = normalizedVaultPath.endsWith(File.separator) ? normalizedVaultPath : normalizedVaultPath + File.separator;
        return normalizedFilePath.startsWith(prefix);
    }

    public static class SyncSummary {
        public final int totalScanned;
        public final int newFiles;
        public final int modifiedFiles;
        public final int unchangedFiles;
        public final int deletedFiles;
        public final int failedFiles;
        public final int skippedEmptyFiles;

        public SyncSummary(int totalScanned, int newFiles, int modifiedFiles, int unchangedFiles, int deletedFiles, int failedFiles, int skippedEmptyFiles) {
            this.totalScanned = totalScanned;
            this.newFiles = newFiles;
            this.modifiedFiles = modifiedFiles;
            this.unchangedFiles = unchangedFiles;
            this.deletedFiles = deletedFiles;
            this.failedFiles = failedFiles;
            this.skippedEmptyFiles = skippedEmptyFiles;
        }

        public String toLogMessage() {
            return String.format("total=%d, new=%d, modified=%d, unchanged=%d, deleted=%d, failed=%d, skippedEmpty=%d",
                    totalScanned, newFiles, modifiedFiles, unchangedFiles, deletedFiles, failedFiles, skippedEmptyFiles);
        }
    }

    private enum AddStatus {
        ADDED,
        SKIPPED_EMPTY,
        FAILED
    }
}








