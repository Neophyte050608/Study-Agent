package io.github.imzmq.interview.ingestion.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.imzmq.interview.ingestion.infrastructure.persistence.SyncIndexDO;
import io.github.imzmq.interview.ingestion.application.contract.IngestionOperations;
import io.github.imzmq.interview.ingestion.application.contract.IngestionUpload;
import io.github.imzmq.interview.ingestion.application.contract.result.IngestionSyncSummary;
import io.github.imzmq.interview.ingestion.application.contract.result.LoadedNote;
import io.github.imzmq.interview.knowledge.application.contract.command.KnowledgeGraphIndexCommand;
import io.github.imzmq.interview.knowledge.application.contract.command.KnowledgeGraphIndexCommand.KnowledgeGraphSourceDocument;
import io.github.imzmq.interview.knowledge.application.contract.indexing.KnowledgeDocumentSplitOperations;
import io.github.imzmq.interview.knowledge.application.contract.indexing.KnowledgeIndexDocument;
import io.github.imzmq.interview.knowledge.application.contract.indexing.KnowledgeLexicalIndexOperations;
import io.github.imzmq.interview.knowledge.application.contract.indexing.KnowledgeParentChildIndexOperations;
import io.github.imzmq.interview.ingestion.infrastructure.persistence.SyncIndexMapper;
import io.github.imzmq.interview.media.application.contract.MediaImageOperations;
import io.github.imzmq.interview.media.application.contract.MediaDocument;
import io.github.imzmq.interview.ingestion.application.NoteLoader;
import io.github.imzmq.interview.ingestion.application.ObsidianKnowledgeExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.util.StreamUtils;

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
public class IngestionService implements IngestionOperations {

    private static final Logger logger = LoggerFactory.getLogger(IngestionService.class);
    private static final Path BROWSER_ASSET_ROOT = Path.of("uploads", "browser-assets");

    private final NoteLoader noteLoader;
    private final KnowledgeDocumentSplitOperations documentSplitter;
    private final ObsidianKnowledgeExtractor knowledgeExtractor;
    private final KnowledgeLexicalIndexOperations lexicalIndexService;
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final SyncIndexMapper syncIndexMapper;
    private final KnowledgeParentChildIndexOperations parentChildIndexService;
    private final MediaImageOperations imageService;
    private final KnowledgeGraphIndexCommand knowledgeGraphIndexCommand;

    private volatile IngestionSyncSummary lastSummary = new IngestionSyncSummary(0, 0, 0, 0, 0, 0, 0);
    private volatile long lastSyncTime = 0L;

    public IngestionService(NoteLoader noteLoader, KnowledgeDocumentSplitOperations documentSplitter, ObsidianKnowledgeExtractor knowledgeExtractor, KnowledgeLexicalIndexOperations lexicalIndexService, ObjectProvider<VectorStore> vectorStoreProvider, SyncIndexMapper syncIndexMapper, KnowledgeParentChildIndexOperations parentChildIndexService, MediaImageOperations imageService, KnowledgeGraphIndexCommand knowledgeGraphIndexCommand) {
        this.noteLoader = noteLoader;
        this.documentSplitter = documentSplitter;
        this.knowledgeExtractor = knowledgeExtractor;
        this.lexicalIndexService = lexicalIndexService;
        this.vectorStoreProvider = vectorStoreProvider;
        this.syncIndexMapper = syncIndexMapper;
        this.parentChildIndexService = parentChildIndexService;
        this.imageService = imageService;
        this.knowledgeGraphIndexCommand = knowledgeGraphIndexCommand;
    }

    public IngestionSyncSummary sync(String vaultPath) {
        return sync(vaultPath, null);
    }

    public IngestionSyncSummary sync(String vaultPath, List<String> ignoredDirs) {
        logger.info("Starting sync for vault: {}", vaultPath);
        List<LoadedNote> resources = noteLoader.loadNotes(vaultPath, ignoredDirs);
        String normalizedVaultPath = normalizeLocalPath(vaultPath);
        Set<String> currentFiles = new HashSet<>();
        int newFileCount = 0;
        int modifiedFileCount = 0;
        int unchangedFileCount = 0;
        int failedFileCount = 0;
        int skippedEmptyFileCount = 0;

        for (LoadedNote resource : resources) {
            try {
                String filePath = resource.path().toString();
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
                logger.error("Error processing file: {}", resource.path().getFileName().toString(), e);
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

        IngestionSyncSummary summary = new IngestionSyncSummary(
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

    public IngestionSyncSummary forceReindexParentChild(String vaultPath, List<String> ignoredDirs) {
        logger.info("Starting force reindex for vault: {}", vaultPath);
        List<LoadedNote> resources = noteLoader.loadNotes(vaultPath, ignoredDirs);
        String normalizedVaultPath = normalizeLocalPath(vaultPath);
        Set<String> currentFiles = new HashSet<>();
        int newFileCount = 0;
        int modifiedFileCount = 0;
        int unchangedFileCount = 0;
        int failedFileCount = 0;
        int skippedEmptyFileCount = 0;

        for (LoadedNote resource : resources) {
            try {
                String filePath = resource.path().toString();
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
                logger.error("Error processing file during force reindex: {}", resource.path().getFileName().toString(), e);
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

        IngestionSyncSummary summary = new IngestionSyncSummary(
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

    public IngestionSyncSummary syncUploadedNotes(List<IngestionUpload> files, List<String> relativePaths, String folderName, List<String> ignoredDirs) {
        if (files == null || files.isEmpty()) {
            return new IngestionSyncSummary(0, 0, 0, 0, 0, 0, 0);
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
            IngestionUpload file = files.get(i);
            String relativePath = relativePaths != null && i < relativePaths.size() ? relativePaths.get(i) : file.originalFilename();
            if (relativePath == null || relativePath.isBlank()) {
                relativePath = file.originalFilename();
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

        IngestionSyncSummary summary = new IngestionSyncSummary(
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
            LoadedNote resource = new LoadedNote(file.toPath());
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

    private void persistUploadedAssets(List<IngestionUpload> files, List<String> relativePaths, String folderKey) {
        if (files == null || files.isEmpty()) {
            return;
        }
        Path baseDir = BROWSER_ASSET_ROOT.resolve(folderKey).toAbsolutePath().normalize();
        for (int i = 0; i < files.size(); i++) {
            IngestionUpload file = files.get(i);
            String relativePath = relativePaths != null && i < relativePaths.size() ? relativePaths.get(i) : file.originalFilename();
            if (relativePath == null || relativePath.isBlank()) {
                relativePath = file.originalFilename();
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
                try (InputStream inputStream = file.inputStream()) {
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

    private AddStatus addFile(LoadedNote resource, String filePath, String hash) {
        try (InputStream inputStream = Files.newInputStream(resource.path())) {
            String content = StreamUtils.copyToString(inputStream, StandardCharsets.UTF_8);
            if (content == null || content.trim().isEmpty()) {
                logger.info("Skip empty file: {}", filePath);
                return AddStatus.SKIPPED_EMPTY;
            }
            List<Document> documents = splitIntoKnowledgeDocuments(content, filePath);
            if (documents.isEmpty()) {
                logger.info("Skip file without valid knowledge blocks: {}", filePath);
                return AddStatus.SKIPPED_EMPTY;
            }
            addVectors(documents);
            lexicalIndexService.upsert(toIndexDocuments(documents));
            parentChildIndexService.rebuild(filePath, toIndexDocuments(documents));
            imageService.indexImagesForDocument(filePath, content, toMediaDocuments(documents));
            
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

    private AddStatus updateFile(LoadedNote resource, String filePath, String hash, List<String> oldDocIds) {
        removeFile(filePath, oldDocIds); // Delete old vectors
        return addFile(resource, filePath, hash); // Add new vectors
    }

    private AddStatus addFile(IngestionUpload file, String filePath, String hash) {
        try {
            String content = new String(file.bytes(), StandardCharsets.UTF_8);
            if (content.trim().isEmpty()) {
                return AddStatus.SKIPPED_EMPTY;
            }
            List<Document> documents = splitIntoKnowledgeDocuments(content, filePath);
            if (documents.isEmpty()) {
                return AddStatus.SKIPPED_EMPTY;
            }
            addVectors(documents);
            lexicalIndexService.upsert(toIndexDocuments(documents));
            parentChildIndexService.rebuild(filePath, toIndexDocuments(documents));
            imageService.indexImagesForDocument(filePath, content, toMediaDocuments(documents));
            
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

    private AddStatus updateFile(IngestionUpload file, String filePath, String hash, List<String> oldDocIds) {
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
        List<Document> chunks = toDocuments(documentSplitter.split(toIndexDocuments(extractionResult.documents())));
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

    private List<KnowledgeIndexDocument> toIndexDocuments(List<Document> documents) {
        return documents.stream()
                .map(document -> new KnowledgeIndexDocument(document.getId(), document.getText(), document.getMetadata()))
                .toList();
    }

    private List<Document> toDocuments(List<KnowledgeIndexDocument> documents) {
        return documents.stream()
                .map(document -> new Document(document.id(), document.text(), document.metadata()))
                .toList();
    }

    private List<MediaDocument> toMediaDocuments(List<Document> documents) {
        return documents.stream()
                .map(document -> new MediaDocument(document.getId(), document.getText(), document.getMetadata()))
                .toList();
    }

    private void processGraphRelationships(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        try {
            for (Document document : documents) {
                Object wikiLinks = document.getMetadata().get("wiki_links");
                knowledgeGraphIndexCommand.index(new KnowledgeGraphSourceDocument(
                        (String) document.getMetadata().get("file_path"),
                        document.getText(),
                        wikiLinks == null ? null : wikiLinks.toString()
                ));
            }
            logger.info("GraphRAG: 成功处理并同步了文档的图谱关系至 Neo4j");
        } catch (Exception e) {
            logger.error("GraphRAG: 同步图谱关系失败", e);
        }
    }


    private void addVectors(List<Document> documents) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            logger.debug("Skip vector indexing because VectorStore is unavailable");
            return;
        }
        vectorStore.add(documents);
    }

    private void deleteVectors(List<String> docIds) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            logger.debug("Skip vector deletion because VectorStore is unavailable");
            return;
        }
        vectorStore.delete(docIds);
    }

    private void removeFile(String filePath, List<String> docIds) {
        if (docIds != null && !docIds.isEmpty()) {
            deleteVectors(docIds);
            lexicalIndexService.removeByIds(docIds);
        }
        parentChildIndexService.deleteByFilePath(filePath);
        syncIndexMapper.delete(new LambdaQueryWrapper<SyncIndexDO>().eq(SyncIndexDO::getFilePath, filePath));
    }

    private String calculateHash(LoadedNote resource) throws IOException {
        try (InputStream is = Files.newInputStream(resource.path())) {
            return DigestUtils.md5DigestAsHex(is);
        }
    }

    private String calculateHash(IngestionUpload file) throws IOException {
        try (InputStream is = file.inputStream()) {
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

    private enum AddStatus {
        ADDED,
        SKIPPED_EMPTY,
        FAILED
    }
}








