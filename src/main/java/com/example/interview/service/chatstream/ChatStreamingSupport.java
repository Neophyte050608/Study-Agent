package com.example.interview.service.chatstream;

import com.example.interview.entity.ChatMessageDO;
import com.example.interview.service.WebChatService;
import com.example.interview.stream.InterviewStreamEventType;
import com.example.interview.stream.InterviewStreamTaskManager;
import com.example.interview.stream.StreamEventEmitter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ChatStreamingSupport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final WebChatService webChatService;
    private final InterviewStreamTaskManager taskManager;
    private final ChunkedTextStreamer chunkedTextStreamer;

    public ChatStreamingSupport(WebChatService webChatService,
                                InterviewStreamTaskManager taskManager) {
        this.webChatService = webChatService;
        this.taskManager = taskManager;
        this.chunkedTextStreamer = new ChunkedTextStreamer();
    }

    public ChatMessageDO createRunningAssistantPlaceholder(String sessionId, String traceId, String taskId) {
        return webChatService.createAssistantPlaceholder(
                sessionId,
                "正在生成中...",
                buildTerminalMetadata(traceId, taskId, "RUNNING", null),
                "text"
        );
    }

    public void updateAssistantPlaceholder(String messageId,
                                           String content,
                                           Map<String, Object> metadata,
                                           String contentType,
                                           String generationStatus) {
        Map<String, Object> merged = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
        if (generationStatus != null && !generationStatus.isBlank()) {
            merged.put("generationStatus", generationStatus);
        }
        webChatService.updateAssistantMessage(messageId, content, merged, contentType);
    }

    public Map<String, Object> buildTerminalMetadata(String traceId,
                                                     String taskId,
                                                     String generationStatus,
                                                     String errorMessage) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("traceId", traceId);
        metadata.put("streamTaskId", taskId);
        metadata.put("generationStatus", generationStatus);
        if (errorMessage != null && !errorMessage.isBlank()) {
            metadata.put("generationError", errorMessage);
        }
        return metadata;
    }

    public void sendChunkedText(StreamEventEmitter emitter, String text, String taskId) {
        chunkedTextStreamer.stream(emitter, text, () -> taskManager.isCancelled(taskId));
    }

    public void sendImageEvents(StreamEventEmitter emitter, List<?> images, String taskId) {
        for (Object image : images) {
            if (taskManager.isCancelled(taskId)) {
                return;
            }
            emitter.emit(InterviewStreamEventType.IMAGE.value(), image);
        }
    }

    public String buildRichContent(String text, List<?> images) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("text", text);
            List<Map<String, Object>> normalizedImages = new java.util.ArrayList<>();
            int position = text == null ? 0 : text.length();
            for (Object image : images) {
                Map<String, Object> normalized = normalizeImagePayload(image, position);
                if (normalized != null && !normalized.isEmpty()) {
                    normalizedImages.add(normalized);
                }
            }
            payload.put("images", normalizedImages);
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (Exception ignored) {
            return text;
        }
    }

    private Map<String, Object> normalizeImagePayload(Object image, int position) {
        if (image == null) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = image instanceof Map<?, ?> map
                    ? OBJECT_MAPPER.convertValue(map, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {})
                    : OBJECT_MAPPER.convertValue(image, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});

            Object nestedValue = raw.get("value");
            if (nestedValue != null) {
                raw = OBJECT_MAPPER.convertValue(nestedValue, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            }

            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("imageId", raw.get("imageId"));
            normalized.put("imageName", raw.get("imageName"));
            normalized.put("accessUrl", raw.get("accessUrl"));
            normalized.put("thumbnailUrl", raw.get("thumbnailUrl"));
            normalized.put("summaryText", raw.get("summaryText"));
            normalized.put("retrieveChannel", raw.get("retrieveChannel"));
            normalized.put("position", position);
            return normalized;
        } catch (Exception ignored) {
            return Map.of("position", position);
        }
    }

    public String toJson(Object value, String fallbackText) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception ignored) {
            return fallbackText;
        }
    }

    public boolean isCancelled(String taskId) {
        return taskManager.isCancelled(taskId);
    }

    public void completeTask(String taskId, StreamEventEmitter emitter) {
        taskManager.unregister(taskId);
        emitter.complete();
    }
}
