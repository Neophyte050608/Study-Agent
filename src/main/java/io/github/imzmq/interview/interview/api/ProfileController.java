package io.github.imzmq.interview.interview.api;

import io.github.imzmq.interview.identity.application.UserIdentityResolver;
import io.github.imzmq.interview.interview.application.InterviewService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ProfileController {

    private static final Logger logger = LoggerFactory.getLogger(ProfileController.class);
    private static final long MAX_RESUME_SIZE_BYTES = 20L * 1024 * 1024;

    private final InterviewService interviewService;
    private final UserIdentityResolver userIdentityResolver;

    public ProfileController(
            InterviewService interviewService,
            UserIdentityResolver userIdentityResolver
    ) {
        this.interviewService = interviewService;
        this.userIdentityResolver = userIdentityResolver;
    }

    /**
     * 获取指定主题的能力掌握曲线。
     */
    @GetMapping("/profile/topic-capability")
    public ResponseEntity<?> topicCapability(@RequestParam("topic") String topic, HttpServletRequest request) {
        String userId = userIdentityResolver.resolve(request);
        return ResponseEntity.ok(interviewService.getTopicCapabilityCurve(userId, topic));
    }

    /**
     * 获取用户画像总览。
     */
    @GetMapping("/profile/overview")
    public ResponseEntity<?> profileOverview(HttpServletRequest request) {
        String userId = userIdentityResolver.resolve(request);
        return ResponseEntity.ok(interviewService.getProfileOverview(userId));
    }

    /**
     * 获取个性化学习/面试建议。
     *
     * @param mode 模式（如 interview 或 learning）
     * @param request HTTP 请求
     * @return 建议内容
     */
    @GetMapping("/profile/recommendations")
    public ResponseEntity<?> profileRecommendations(
            @RequestParam(value = "mode", required = false, defaultValue = "interview") String mode,
            HttpServletRequest request
    ) {
        String userId = userIdentityResolver.resolve(request);
        return ResponseEntity.ok(Map.of(
                "mode", mode,
                "recommendation", interviewService.getProfileRecommendation(userId, mode)
        ));
    }

    /**
     * 上传 PDF 简历。
     * 仅保留最近一次上传的简历。
     */
    @PostMapping("/resume/upload")
    public ResponseEntity<?> uploadResume(@RequestParam("file") MultipartFile file) {
        // 上传简历：做大小/扩展名/Content-Type 三重校验，减少非 PDF 内容带来的解析风险。
        if (file.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "请上传 PDF 简历文件"));
        }
        if (file.getSize() > MAX_RESUME_SIZE_BYTES) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "简历文件过大，请上传 20MB 以内 PDF"));
        }
        String originalName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!originalName.endsWith(".pdf")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "仅支持 PDF 简历"));
        }
        String contentType = file.getContentType();
        if (contentType != null && !contentType.isBlank()
                && !contentType.equalsIgnoreCase("application/pdf")
                && !contentType.equalsIgnoreCase("application/octet-stream")
                && !contentType.toLowerCase().startsWith("application/pdf")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", "文件类型不是有效 PDF"));
        }
        try {
            Path uploadDir = Paths.get("uploads", "resumes");

            // 如果目录已存在，则清理里面旧的简历文件
            if (Files.exists(uploadDir)) {
                try (java.util.stream.Stream<Path> stream = Files.list(uploadDir)) {
                    stream.filter(path -> path.toString().toLowerCase().endsWith(".pdf"))
                          .forEach(path -> {
                              try {
                                  Files.deleteIfExists(path);
                              } catch (IOException e) {
                                  logger.warn("Failed to delete old resume: {}", path, e);
                              }
                          });
                }
            } else {
                Files.createDirectories(uploadDir);
            }

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
}
