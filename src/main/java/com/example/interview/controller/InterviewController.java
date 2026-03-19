package com.example.interview.controller;

import com.example.interview.core.InterviewSession;
import com.example.interview.service.IngestionService;
import com.example.interview.service.InterviewService;
import com.example.interview.service.RetrievalEvaluationService;
import com.example.interview.service.UserIdentityResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 面试系统 HTTP 入口层。
 * 负责参数解析、基础校验、错误码映射，并把业务请求转发到服务层。
 */
@RestController
@RequestMapping("/api")
public class InterviewController {

    private static final Logger logger = LoggerFactory.getLogger(InterviewController.class);
    private static final long MAX_RESUME_SIZE_BYTES = 10L * 1024 * 1024;

    private final InterviewService interviewService;
    private final IngestionService ingestionService;
    private final UserIdentityResolver userIdentityResolver;

    public InterviewController(InterviewService interviewService, IngestionService ingestionService, UserIdentityResolver userIdentityResolver) {
        this.interviewService = interviewService;
        this.ingestionService = ingestionService;
        this.userIdentityResolver = userIdentityResolver;
    }

    @PostMapping("/ingest")
    public ResponseEntity<Map<String, String>> ingest(@RequestBody Map<String, String> payload) {
        String path = payload.get("path");
        String ignoreDirs = payload.get("ignoreDirs");
        List<String> ignoredList = parseIgnoreDirs(ignoreDirs);
        try {
            IngestionService.SyncSummary summary = ingestionService.sync(path, ignoredList);
            String message = String.format(
                    "同步完成：共扫描 %d 个文件，新增 %d，修改 %d，未变化 %d，删除 %d，失败 %d，空内容跳过 %d",
                    summary.totalScanned,
                    summary.newFiles,
                    summary.modifiedFiles,
                    summary.unchangedFiles,
                    summary.deletedFiles,
                    summary.failedFiles,
                    summary.skippedEmptyFiles
            );
            return ResponseEntity.ok(Map.of("message", message));
        } catch (Exception e) {
            String message = e.getMessage() == null ? "同步失败，请检查配置" : e.getMessage();
            if (message.contains("401")) {
                message = "同步失败：Embedding API 认证失败，请检查智谱 API Key 是否有效";
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", message));
        }
    }

    @PostMapping("/ingest-files")
    public ResponseEntity<Map<String, String>> ingestFiles(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "relativePaths", required = false) List<String> relativePaths,
            @RequestParam(value = "folderName", required = false) String folderName,
            @RequestParam(value = "ignoreDirs", required = false) String ignoreDirs) {
        try {
            List<String> ignoredList = parseIgnoreDirs(ignoreDirs);
            IngestionService.SyncSummary summary = ingestionService.syncUploadedNotes(files, relativePaths, folderName, ignoredList);
            String message = String.format(
                    "同步完成：共扫描 %d 个文件，新增 %d，修改 %d，未变化 %d，删除 %d，失败 %d，空内容跳过 %d",
                    summary.totalScanned,
                    summary.newFiles,
                    summary.modifiedFiles,
                    summary.unchangedFiles,
                    summary.deletedFiles,
                    summary.failedFiles,
                    summary.skippedEmptyFiles
            );
            return ResponseEntity.ok(Map.of("message", message));
        } catch (Exception e) {
            String message = e.getMessage() == null ? "同步失败，请检查文件内容" : e.getMessage();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", message));
        }
    }

    private List<String> parseIgnoreDirs(String ignoreDirs) {
        if (ignoreDirs == null || ignoreDirs.isBlank()) {
            return List.of();
        }
        return Arrays.stream(ignoreDirs.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .collect(Collectors.toList());
    }

    /**
     * 创建一轮新的模拟面试会话，并返回首题。
     */
    @PostMapping("/start")
    public InterviewSession startSession(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
        String userId = userIdentityResolver.resolve(request);
        String topic = payload.get("topic") == null ? "" : payload.get("topic").toString();
        String resumePath = payload.get("resumePath") == null ? "" : payload.get("resumePath").toString();
        Integer totalQuestions = null;
        Object rawTotal = payload.get("totalQuestions");
        if (rawTotal instanceof Number) {
            totalQuestions = ((Number) rawTotal).intValue();
        } else if (rawTotal instanceof String) {
            String raw = (String) rawTotal;
            if (!raw.isBlank()) {
                try {
                    totalQuestions = Integer.parseInt(raw.trim());
                } catch (NumberFormatException ignored) {
                    totalQuestions = null;
                }
            }
        }
        return interviewService.startSession(userId, topic, resumePath, totalQuestions);
    }

    /**
     * 提交当前题目的作答文本，返回评分、反馈和下一题。
     */
    @PostMapping("/answer")
    public ResponseEntity<?> submitAnswer(@RequestBody Map<String, String> payload) {
        String sessionId = payload.get("sessionId");
        String answer = payload.get("answer");
        try {
            return ResponseEntity.ok(interviewService.submitAnswer(sessionId, answer));
        } catch (Exception e) {
            String message = e.getMessage() == null ? "回答分析失败，请稍后重试" : e.getMessage();
            String lowerMessage = message.toLowerCase();
            if (lowerMessage.contains("timeout")) {
                message = "回答分析超时，请稍后重试或简化回答后再试";
            }
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", message));
        }
    }

    /**
     * 结束后生成整场面试总结报告。
     */
    @PostMapping("/report")
    public ResponseEntity<?> report(@RequestBody Map<String, String> payload, HttpServletRequest request) {
        String sessionId = payload.get("sessionId");
        String userId = userIdentityResolver.resolve(request);
        try {
            return ResponseEntity.ok(interviewService.generateFinalReport(sessionId, userId));
        } catch (Exception e) {
            String message = e.getMessage() == null ? "总结生成失败，请稍后重试" : e.getMessage();
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", message));
        }
    }

    @GetMapping("/profile/topic-capability")
    public ResponseEntity<?> topicCapability(@RequestParam("topic") String topic, HttpServletRequest request) {
        String userId = userIdentityResolver.resolve(request);
        return ResponseEntity.ok(interviewService.getTopicCapabilityCurve(userId, topic));
    }

    @GetMapping("/observability/rag-traces")
    public ResponseEntity<?> ragTraces(@RequestParam(value = "limit", required = false, defaultValue = "20") Integer limit) {
        return ResponseEntity.ok(interviewService.getRecentRagTraces(limit == null ? 20 : limit));
    }

    @GetMapping("/observability/retrieval-eval")
    public ResponseEntity<?> retrievalEval() {
        return ResponseEntity.ok(interviewService.runRetrievalOfflineEval());
    }

    @PostMapping("/observability/retrieval-eval/run")
    public ResponseEntity<?> runRetrievalEval(@RequestBody Map<String, Object> payload) {
        List<RetrievalEvaluationService.EvalCase> cases = parseEvalCases(payload.get("cases"));
        if (cases.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "评测用例为空，请提供 cases"));
        }
        return ResponseEntity.ok(interviewService.runRetrievalEvalWithCases(cases));
    }

    @PostMapping("/observability/retrieval-eval/upload")
    public ResponseEntity<?> uploadRetrievalEval(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "请上传评测集文件"));
        }
        try {
            String text = new String(file.getBytes(), StandardCharsets.UTF_8);
            List<RetrievalEvaluationService.EvalCase> cases = interviewService.parseRetrievalEvalCsv(text);
            if (cases.isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "未解析到有效评测用例，请检查 CSV 格式"));
            }
            return ResponseEntity.ok(interviewService.runRetrievalEvalWithCases(cases));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "评测集文件读取失败"));
        }
    }

    @PostMapping("/resume/upload")
    public ResponseEntity<?> uploadResume(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "请上传 PDF 简历文件"));
        }
        if (file.getSize() > MAX_RESUME_SIZE_BYTES) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "简历文件过大，请上传 10MB 以内 PDF"));
        }
        String originalName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!originalName.endsWith(".pdf")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "仅支持 PDF 简历"));
        }
        String contentType = file.getContentType();
        if (contentType != null && !contentType.equalsIgnoreCase("application/pdf")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "文件类型不是有效 PDF"));
        }
        try {
            Path uploadDir = Paths.get("uploads", "resumes");
            Files.createDirectories(uploadDir);
            String fileName = UUID.randomUUID() + ".pdf";
            Path target = uploadDir.resolve(fileName).normalize();
            try (var inputStream = file.getInputStream()) {
                Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return ResponseEntity.ok(Map.of(
                    "resumePath", target.toAbsolutePath().toString(),
                    "fileName", file.getOriginalFilename() == null ? "resume.pdf" : file.getOriginalFilename()
            ));
        } catch (IOException e) {
            logger.error("Resume upload failed", e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", "简历上传失败，请稍后重试"));
        }
    }

    private List<RetrievalEvaluationService.EvalCase> parseEvalCases(Object rawCases) {
        if (!(rawCases instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(item -> {
                    if (!(item instanceof Map<?, ?> map)) {
                        return null;
                    }
                    Object queryObj = map.get("query");
                    String query = queryObj == null ? "" : queryObj.toString().trim();
                    if (query.isBlank()) {
                        return null;
                    }
                    Object keywordsObj = map.get("expectedKeywords");
                    List<String> keywords;
                    if (keywordsObj instanceof List<?> keyList) {
                        keywords = keyList.stream()
                                .map(Object::toString)
                                .map(String::trim)
                                .filter(word -> !word.isBlank())
                                .toList();
                    } else {
                        keywords = List.of();
                    }
                    return new RetrievalEvaluationService.EvalCase(query, keywords);
                })
                .filter(item -> item != null)
                .toList();
    }
}
