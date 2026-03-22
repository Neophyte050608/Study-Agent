package com.example.interview.service;

import com.example.interview.rag.DocumentSplitter;
import com.example.interview.rag.NoteLoader;
import com.example.interview.rag.ObsidianKnowledgeExtractor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.util.StreamUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class IngestionService {

    private static final Logger logger = LoggerFactory.getLogger(IngestionService.class);
    private static final String INDEX_FILE = "sync_index.json";

    private final NoteLoader noteLoader;
    private final DocumentSplitter documentSplitter;
    private final ObsidianKnowledgeExtractor knowledgeExtractor;
    private final LexicalIndexService lexicalIndexService;
    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper;
    // 注入图谱持久化组件
    private final com.example.interview.graph.TechConceptRepository techConceptRepository;

    // Map<FilePath, FileMetadata>
    private Map<String, FileMetadata> index = new ConcurrentHashMap<>();
    private volatile SyncSummary lastSummary = new SyncSummary(0, 0, 0, 0, 0, 0, 0);
    private volatile long lastSyncTime = 0L;

    public IngestionService(NoteLoader noteLoader, DocumentSplitter documentSplitter, ObsidianKnowledgeExtractor knowledgeExtractor, LexicalIndexService lexicalIndexService, VectorStore vectorStore, ObjectMapper objectMapper, com.example.interview.graph.TechConceptRepository techConceptRepository) {
        this.noteLoader = noteLoader;
        this.documentSplitter = documentSplitter;
        this.knowledgeExtractor = knowledgeExtractor;
        this.lexicalIndexService = lexicalIndexService;
        this.vectorStore = vectorStore;
        this.objectMapper = objectMapper;
        this.techConceptRepository = techConceptRepository;
        loadIndex();
    }

    public SyncSummary sync(String vaultPath) {
        return sync(vaultPath, null);
    }

    public SyncSummary sync(String vaultPath, List<String> ignoredDirs) {
        logger.info("Starting sync for vault: {}", vaultPath);
        List<Resource> resources = noteLoader.loadNotes(vaultPath, ignoredDirs);
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

                if (index.containsKey(filePath)) {
                    FileMetadata metadata = index.get(filePath);
                    if (!metadata.hash.equals(currentHash)) {
                        logger.info("File modified: {}", filePath);
                        AddStatus status = updateFile(resource, filePath, currentHash);
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
        List<String> deletedFiles = index.keySet().stream()
                .filter(path -> !currentFiles.contains(path))
                .collect(Collectors.toList());

        for (String deletedPath : deletedFiles) {
            logger.info("File deleted: {}", deletedPath);
            removeFile(deletedPath);
        }

        saveIndex();
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

    public SyncSummary syncUploadedNotes(List<MultipartFile> files, List<String> relativePaths, String folderName, List<String> ignoredDirs) {
        if (files == null || files.isEmpty()) {
            return new SyncSummary(0, 0, 0, 0, 0, 0, 0);
        }
        String folderKey = sanitizeFolderKey(folderName);
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
                if (index.containsKey(filePath)) {
                    FileMetadata metadata = index.get(filePath);
                    if (!metadata.hash.equals(currentHash)) {
                        AddStatus status = updateFile(file, filePath, currentHash);
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

        List<String> deletedFiles = index.keySet().stream()
                .filter(path -> path.startsWith(prefix))
                .filter(path -> !currentFiles.contains(path))
                .collect(Collectors.toList());

        for (String deletedPath : deletedFiles) {
            removeFile(deletedPath);
        }

        saveIndex();
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
        result.put("totalScanned", lastSummary.totalScanned);
        result.put("totalIndexed", index.size());
        result.put("failedFiles", lastSummary.failedFiles);
        int denominator = Math.max(1, lastSummary.totalScanned);
        int success = Math.max(0, lastSummary.totalScanned - lastSummary.failedFiles);
        int successRate = Math.max(0, Math.min(100, (success * 100) / denominator));
        result.put("successRate", successRate + "%");
        result.put("lastSyncTime", lastSyncTime == 0L ? null : lastSyncTime);
        result.put("storagePercent", Math.min(100, index.size()));
        result.put("recentReports", List.of());
        return result;
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
            
            // GraphRAG: 提取双向链接并写入 Neo4j
            processGraphRelationships(documents);
            
            List<String> docIds = documents.stream().map(Document::getId).collect(Collectors.toList());
            index.put(filePath, new FileMetadata(hash, docIds));
            return AddStatus.ADDED;
        } catch (Exception e) {
            logger.error("Failed to add file to vector store: {}", filePath, e);
            return AddStatus.FAILED;
        }
    }

    private AddStatus updateFile(Resource resource, String filePath, String hash) {
        removeFile(filePath); // Delete old vectors
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
            
            // GraphRAG: 提取双向链接并写入 Neo4j
            processGraphRelationships(documents);
            
            List<String> docIds = documents.stream().map(Document::getId).collect(Collectors.toList());
            index.put(filePath, new FileMetadata(hash, docIds));
            return AddStatus.ADDED;
        } catch (Exception e) {
            logger.error("Failed to add uploaded file to vector store: {}", filePath, e);
            return AddStatus.FAILED;
        }
    }

    private AddStatus updateFile(MultipartFile file, String filePath, String hash) {
        removeFile(filePath);
        return addFile(file, filePath, hash);
    }

    private List<Document> splitIntoKnowledgeDocuments(String content, String filePath) {
        ObsidianKnowledgeExtractor.ExtractionResult extractionResult = knowledgeExtractor.extract(content, filePath);
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

    /**
     * 解析文档中的元数据（尤其是 wiki_links），并将其作为节点和关系存入 Neo4j 图数据库。
     */
    private void processGraphRelationships(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        try {
            for (Document doc : documents) {
                String filePath = (String) doc.getMetadata().get("file_path");
                if (filePath == null) continue;

                // 提取文件名作为当前节点（主节点）
                String fileName = new File(filePath).getName();
                int dotIndex = fileName.lastIndexOf('.');
                String mainConceptName = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;

                // 获取或创建主节点
                com.example.interview.graph.TechConcept mainConcept = techConceptRepository.findById(mainConceptName)
                        .orElse(new com.example.interview.graph.TechConcept(mainConceptName));
                
                // 将部分正文作为描述存入节点
                if (mainConcept.getDescription() == null) {
                    mainConcept.setDescription(doc.getText().length() > 200 ? doc.getText().substring(0, 200) + "..." : doc.getText());
                }

                // 提取关联的 wiki_links（例如 [[Redis]] -> Redis）
                Object wikiLinksObj = doc.getMetadata().get("wiki_links");
                if (wikiLinksObj != null && !wikiLinksObj.toString().isBlank()) {
                    String[] links = wikiLinksObj.toString().split(",");
                    for (String link : links) {
                        String relatedName = link.trim();
                        if (!relatedName.isBlank() && !relatedName.equals(mainConceptName)) {
                            // 获取或创建关联节点
                            com.example.interview.graph.TechConcept relatedConcept = techConceptRepository.findById(relatedName)
                                    .orElse(new com.example.interview.graph.TechConcept(relatedName));
                            // 在 Neo4j 中建立关系：(mainConcept) -[:RELATED_TO]-> (relatedConcept)
                            mainConcept.addRelatedConcept(relatedConcept);
                            // 保存关联节点以防其不存在
                            techConceptRepository.save(relatedConcept);
                        }
                    }
                }
                // 保存主节点及其关系
                techConceptRepository.save(mainConcept);
            }
            logger.info("GraphRAG: 成功处理并同步了文档的图谱关系至 Neo4j");
        } catch (Exception e) {
            logger.error("GraphRAG: 同步图谱关系失败", e);
        }
    }

    private void removeFile(String filePath) {
        FileMetadata metadata = index.get(filePath);
        if (metadata != null && metadata.docIds != null && !metadata.docIds.isEmpty()) {
            vectorStore.delete(metadata.docIds);
            lexicalIndexService.removeByIds(metadata.docIds);
        }
        index.remove(filePath);
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

    private void loadIndex() {
        File file = new File(INDEX_FILE);
        if (file.exists()) {
            try {
                index = objectMapper.readValue(file, new TypeReference<ConcurrentHashMap<String, FileMetadata>>() {});
                logger.info("Loaded sync index with {} entries.", index.size());
            } catch (IOException e) {
                logger.error("Failed to load sync index.", e);
            }
        }
    }

    private void saveIndex() {
        try {
            objectMapper.writeValue(new File(INDEX_FILE), index);
            logger.info("Saved sync index.");
        } catch (IOException e) {
            logger.error("Failed to save sync index.", e);
        }
    }

    // Helper class for JSON serialization
    public static class FileMetadata {
        public String hash;
        public List<String> docIds;

        public FileMetadata() {}

        public FileMetadata(String hash, List<String> docIds) {
            this.hash = hash;
            this.docIds = docIds;
        }
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
