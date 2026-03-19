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

    // Map<FilePath, FileMetadata>
    private Map<String, FileMetadata> index = new ConcurrentHashMap<>();

    public IngestionService(NoteLoader noteLoader, DocumentSplitter documentSplitter, ObsidianKnowledgeExtractor knowledgeExtractor, LexicalIndexService lexicalIndexService, VectorStore vectorStore, ObjectMapper objectMapper) {
        this.noteLoader = noteLoader;
        this.documentSplitter = documentSplitter;
        this.knowledgeExtractor = knowledgeExtractor;
        this.lexicalIndexService = lexicalIndexService;
        this.vectorStore = vectorStore;
        this.objectMapper = objectMapper;
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
        return new SyncSummary(
                currentFiles.size(),
                newFileCount,
                modifiedFileCount,
                unchangedFileCount,
                deletedFiles.size(),
                failedFileCount,
                skippedEmptyFileCount
        );
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
                })
                .collect(Collectors.toList());
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
