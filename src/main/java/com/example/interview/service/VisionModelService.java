package com.example.interview.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class VisionModelService {

    private static final Logger logger = LoggerFactory.getLogger(VisionModelService.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String apiKey;
    private final String model;

    public VisionModelService(RestClient.Builder restClientBuilder,
                              ObjectMapper objectMapper,
                              @Value("${app.multimodal.vlm.enabled:false}") boolean enabled,
                              @Value("${app.multimodal.vlm.api-key:}") String apiKey,
                              @Value("${app.multimodal.vlm.base-url:https://open.bigmodel.cn/api/paas/v4}") String baseUrl,
                              @Value("${app.multimodal.vlm.model:glm-4v}") String model) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
    }

    public String summarize(Path imagePath, String fallbackName) {
        if (imagePath == null) {
            return fallback(fallbackName);
        }
        if (!enabled || apiKey.isBlank()) {
            return fallback(fallbackName);
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(imagePath);
            String base64 = Base64.getEncoder().encodeToString(bytes);
            String mimeType = Files.probeContentType(imagePath);
            if (mimeType == null || mimeType.isBlank()) {
                mimeType = "image/png";
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", List.of(Map.of(
                    "role", "user",
                    "content", List.of(
                            Map.of("type", "text", "text", """
                                    你是技术文档图片分析专家。请分析这张图片并输出 JSON：
                                    1. type: 图片类型（架构图/代码截图/流程图/配置截图/其他）
                                    2. keywords: 核心技术关键词数组
                                    3. description: 50-150字描述
                                    """),
                            Map.of("type", "image_url", "image_url", Map.of("url", "data:" + mimeType + ";base64," + base64))
                    )
            )));
            String raw = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            String summary = parseSummary(raw, fallbackName);
            return summary == null || summary.isBlank() ? fallback(fallbackName) : summary;
        } catch (Exception e) {
            logger.warn("Vision summary failed for {}", imagePath, e);
            return fallback(fallbackName);
        }
    }

    private String parseSummary(String raw, String fallbackName) {
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            String text = content.asText("");
            if (text.isBlank()) {
                return fallback(fallbackName);
            }
            JsonNode node = objectMapper.readTree(text);
            String type = node.path("type").asText("其他");
            String description = node.path("description").asText("");
            List<String> keywords = objectMapper.convertValue(node.path("keywords"), objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            String joined = (keywords == null || keywords.isEmpty()) ? "" : String.join("、", keywords);
            return ("[" + type + "] " + description + (joined.isBlank() ? "" : " 关键词: " + joined)).trim();
        } catch (Exception ignored) {
            return fallback(fallbackName);
        }
    }

    private String fallback(String fallbackName) {
        return "图片：" + (fallbackName == null || fallbackName.isBlank() ? "未命名图片" : fallbackName);
    }
}
