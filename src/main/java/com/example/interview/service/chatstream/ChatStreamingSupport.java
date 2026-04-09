package com.example.interview.service.chatstream;

import com.example.interview.entity.ChatMessageDO;
import com.example.interview.service.WebChatService;
import com.example.interview.stream.InterviewSseEmitterSender;
import com.example.interview.stream.InterviewStreamEventType;
import com.example.interview.stream.InterviewStreamTaskManager;
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

    public void sendChunkedText(InterviewSseEmitterSender sender, String text, String taskId) {
        chunkedTextStreamer.stream(sender, text, () -> taskManager.isCancelled(taskId));
    }

    public void sendImageEvents(InterviewSseEmitterSender sender, List<?> images, String taskId) {
        for (Object image : images) {
            if (taskManager.isCancelled(taskId)) {
                return;
            }
            sender.sendEvent(InterviewStreamEventType.IMAGE.value(), image);
        }
    }

    public String buildRichContent(String text, List<?> images) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("text", text);
            List<Map<String, Object>> normalizedImages = new java.util.ArrayList<>();
            int position = text == null ? 0 : text.length();
            for (Object image : images) {
                if (image instanceof Map<?, ?> map) {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    normalized.put("imageId", map.get("imageId"));
                    normalized.put("imageName", map.get("imageName"));
                    normalized.put("accessUrl", map.get("accessUrl"));
                    normalized.put("thumbnailUrl", map.get("thumbnailUrl"));
                    normalized.put("summaryText", map.get("summaryText"));
                    normalized.put("retrieveChannel", map.get("retrieveChannel"));
                    normalized.put("position", position);
                    normalizedImages.add(normalized);
                } else {
                    normalizedImages.add(Map.of("position", position, "value", image));
                }
            }
            payload.put("images", normalizedImages);
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (Exception ignored) {
            return text;
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

    public void completeTask(String taskId, InterviewSseEmitterSender sender) {
        taskManager.unregister(taskId);
        sender.complete();
    }
}
