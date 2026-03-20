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

/**
 * 知识入库服务 (Ingestion Service)。
 * 
 * 该服务负责将外部知识（本地 Markdown 笔记或浏览器上传的文件）同步到向量数据库。
 * 
 * 核心流程：
 * 1. 扫描 (Scanning)：发现目标目录下的所有 .md 文件。
 * 2. 增量校验 (Change Detection)：利用 MD5 指纹判断文件是否有变化。
 * 3. 结构化提取 (Extraction)：利用 ObsidianKnowledgeExtractor 提取标题、关键词、代码块等。
 * 4. 切分 (Splitting)：利用 DocumentSplitter 将文档切分为更小的语义块。
 * 5. 存储 (Storage)：将切分后的文档存入向量库 (Milvus) 并同步更新全文搜索索引。
 * 6. 状态持久化 (Index Persistence)：将文件指纹与向量 ID 的映射保存到 sync_index.json。
 */
@Service
public class IngestionService {

    private static final Logger logger = LoggerFactory.getLogger(IngestionService.class);
    /** 同步索引文件名 */
    private static final String INDEX_FILE = "sync_index.json";

    private final NoteLoader noteLoader;
    private final DocumentSplitter documentSplitter;
    private final ObsidianKnowledgeExtractor knowledgeExtractor;
    private final LexicalIndexService lexicalIndexService;
    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper;

    /** 同步状态索引：Map<文件绝对路径, 文件元数据(MD5+向量ID列表)> */
    private Map<String, FileMetadata> index = new ConcurrentHashMap<>();

    /** 最近处理的文件报告记录，用于前端 UI 展示 */
    private final List<FileReport> recentReports = Collections.synchronizedList(new ArrayList<>());
    private static final int MAX_REPORTS = 10;

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

    /**
     * 同步本地目录知识。
     * 
     * @param vaultPath 本地笔记库的绝对路径
     * @param ignoredDirs 需要忽略的子目录名称列表（如：templates, attachment）
     * @return 同步结果摘要，包含扫描数、新增数、修改数、删除数等统计
     */
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

        // 处理物理删除的文件：从向量库中同步移除对应的向量块
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

    /**
     * 同步浏览器上传的文件（适用于非本地运行场景）。
     */
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

    /** 处理新增文件入库 */
    private AddStatus addFile(Resource resource, String filePath, String hash) {
        String fileName = resource.getFilename();
        if (fileName == null) fileName = "unknown";
        try {
            String content = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            if (content == null || content.trim().isEmpty()) {
                logger.info("Skip empty file: {}", filePath);
                addReport(fileName, "SKIPPED", "内容为空 • 跳过", "article");
                return AddStatus.SKIPPED_EMPTY;
            }
            List<Document> documents = splitIntoKnowledgeDocuments(content, filePath);
            if (documents.isEmpty()) {
                logger.info("Skip file without valid knowledge blocks: {}", filePath);
                addReport(fileName, "SKIPPED", "未提取到知识块 • 跳过", "article");
                return AddStatus.SKIPPED_EMPTY;
            }
            vectorStore.add(documents);
            lexicalIndexService.upsertDocuments(documents);
            
            List<String> docIds = documents.stream().map(Document::getId).collect(Collectors.toList());
            index.put(filePath, new FileMetadata(hash, docIds));
            addReport(fileName, "SUCCESS", "向量化完成 • " + documents.size() + " 个分块", "description");
            return AddStatus.ADDED;
        } catch (Exception e) {
            logger.error("Failed to add file to vector store: {}", filePath, e);
            addReport(fileName, "FAILED", "解析失败 • " + e.getMessage(), "error");
            return AddStatus.FAILED;
        }
    }

    private AddStatus updateFile(Resource resource, String filePath, String hash) {
        removeFile(filePath); // 先删除旧的向量块
        return addFile(resource, filePath, hash); // 再添加新的
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

    /**
     * 将原始内容切分为适合检索的知识块。
     */
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

    /**
     * 从向量库和全文搜索索引中移除指定文件的所有块。
     */
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

    /** 加载同步索引 */
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

    /** 添加 UI 处理报告 */
    private void addReport(String fileName, String status, String message, String icon) {
        synchronized (recentReports) {
            recentReports.add(0, new FileReport(fileName, status, message, icon));
            if (recentReports.size() > MAX_REPORTS) {
                recentReports.remove(recentReports.size() - 1);
            }
        }
    }

    /**
     * 获取知识库同步统计数据。
     */
    public Map<String, Object> getStats() {
        int totalFiles = index.size();
        int totalDocs = index.values().stream()
                .filter(m -> m.docIds != null)
                .mapToInt(m -> m.docIds.size())
                .sum();
        
        // 计算模拟存储占用百分比 (用于 UI 展示进度条)
        int maxDocs = 50000;
        int storagePercent = Math.min(100, (int)((totalDocs * 100.0) / maxDocs));
        
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalScanned", totalFiles);
        stats.put("totalIndexed", totalDocs);
        stats.put("successRate", totalFiles > 0 ? "100%" : "0%");
        stats.put("failedFiles", 0);
        stats.put("lastSyncTime", java.time.LocalDateTime.now().toString());
        stats.put("status", "运行正常");
        stats.put("storagePercent", storagePercent);
        
        synchronized (recentReports) {
            stats.put("recentReports", new ArrayList<>(recentReports));
        }
        
        return stats;
    }

    /** 单个文件的同步报告实体 */
    public static class FileReport {
        public String fileName;
        public String status;
        public String message;
        public String icon;

        public FileReport(String fileName, String status, String message, String icon) {
            this.fileName = fileName;
            this.status = status;
            this.message = message;
            this.icon = icon;
        }
    }

    /** 保存同步索引 */
    private void saveIndex() {
        try {
            objectMapper.writeValue(new File(INDEX_FILE), index);
            logger.info("Saved sync index.");
        } catch (IOException e) {
            logger.error("Failed to save sync index.", e);
        }
    }

    /** 文件元数据，记录 MD5 和对应的向量 ID 列表 */
    public static class FileMetadata {
        public String hash;
        public List<String> docIds;

        public FileMetadata() {}

        public FileMetadata(String hash, List<String> docIds) {
            this.hash = hash;
            this.docIds = docIds;
        }
    }

    /** 同步结果摘要 */
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
