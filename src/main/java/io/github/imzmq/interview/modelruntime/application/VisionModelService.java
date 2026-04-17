package io.github.imzmq.interview.modelruntime.application;

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

    public String summarize(Path imagePath, String fallbackName, String sectionPath, String nearbyContext) {
        if (imagePath == null) {
            return fallbackWithContext(fallbackName, sectionPath, nearbyContext);
        }
        if (!enabled || apiKey.isBlank()) {
            return fallbackWithContext(fallbackName, sectionPath, nearbyContext);
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
                            Map.of("type", "text", "text", buildVlmPrompt(sectionPath, nearbyContext)),
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
            String summary = parseSummary(raw, fallbackName, sectionPath, nearbyContext);
            return summary == null || summary.isBlank() ? fallbackWithContext(fallbackName, sectionPath, nearbyContext) : summary;
        } catch (Exception e) {
            logger.warn("Vision summary failed for {}", imagePath, e);
            return fallbackWithContext(fallbackName, sectionPath, nearbyContext);
        }
    }

    private String parseSummary(String raw, String fallbackName, String sectionPath, String nearbyContext) {
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            String text = content.asText("");
            if (text.isBlank()) {
                return fallbackWithContext(fallbackName, sectionPath, nearbyContext);
            }
            JsonNode node = objectMapper.readTree(text);
            String type = node.path("type").asText("其他");
            String description = node.path("description").asText("");
            List<String> keywords = objectMapper.convertValue(node.path("keywords"), objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            String joined = (keywords == null || keywords.isEmpty()) ? "" : String.join("、", keywords);
            return ("[" + type + "] " + description + (joined.isBlank() ? "" : " 关键词: " + joined)).trim();
        } catch (Exception ignored) {
            return fallbackWithContext(fallbackName, sectionPath, nearbyContext);
        }
    }

    private String fallback(String fallbackName) {
        return "图片：" + (fallbackName == null || fallbackName.isBlank() ? "未命名图片" : fallbackName);
    }

    private String buildVlmPrompt(String sectionPath, String nearbyContext) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是技术文档图片分析专家。请分析这张图片并输出 JSON：\n");
        prompt.append("1. type: 图片类型（架构图/代码截图/流程图/配置截图/其他）\n");
        prompt.append("2. keywords: 核心技术关键词数组\n");
        prompt.append("3. description: 50-150字描述，需准确反映图片内容与其在文档中的作用\n");
        if (sectionPath != null && !sectionPath.isBlank()) {
            prompt.append("\n该图片出现在文档章节：").append(sectionPath).append("\n");
        }
        if (nearbyContext != null && !nearbyContext.isBlank()) {
            String trimmed = nearbyContext.length() > 200 ? nearbyContext.substring(0, 200) : nearbyContext;
            prompt.append("图片周围的文档内容：").append(trimmed).append("\n");
        }
        if ((sectionPath != null && !sectionPath.isBlank()) || (nearbyContext != null && !nearbyContext.isBlank())) {
            prompt.append("请结合以上文档上下文，让 description 和 keywords 更贴合文档语境。\n");
        }
        return prompt.toString();
    }

    /**
     * 增强版 fallback：利用文档上下文构建有语义的摘要文本。
     * 当 VLM 不可用时调用，确保 embedding 有内容可索引。
     */
    private String fallbackWithContext(String fallbackName, String sectionPath, String nearbyContext) {
        StringBuilder sb = new StringBuilder();
        if (sectionPath != null && !sectionPath.isBlank()) {
            sb.append("图片（").append(sectionPath).append("）");
        } else {
            sb.append("图片");
        }
        if (nearbyContext != null && !nearbyContext.isBlank()) {
            String trimmed = nearbyContext.length() > 80 ? nearbyContext.substring(0, 80) : nearbyContext;
            sb.append("：").append(trimmed);
        } else if (fallbackName != null && !fallbackName.isBlank()) {
            sb.append("：").append(fallbackName);
        } else {
            sb.append("：未命名图片");
        }
        return sb.toString();
    }
}




