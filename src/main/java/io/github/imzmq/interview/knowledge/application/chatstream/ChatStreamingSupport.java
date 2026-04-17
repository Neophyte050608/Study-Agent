package io.github.imzmq.interview.knowledge.application.chatstream;

import io.github.imzmq.interview.entity.chat.ChatMessageDO;
import io.github.imzmq.interview.interview.application.WebChatService;
import io.github.imzmq.interview.knowledge.application.observability.RAGObservabilityService;
import io.github.imzmq.interview.knowledge.application.observability.TraceNodeDefinitions;
import io.github.imzmq.interview.stream.runtime.InterviewStreamEventType;
import io.github.imzmq.interview.stream.runtime.InterviewStreamTaskManager;
import io.github.imzmq.interview.stream.runtime.StreamEventEmitter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

@Component
public class ChatStreamingSupport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final WebChatService webChatService;
    private final InterviewStreamTaskManager taskManager;
    private final ChunkedTextStreamer chunkedTextStreamer;
    private final RAGObservabilityService ragObservabilityService;

    public ChatStreamingSupport(WebChatService webChatService,
                                InterviewStreamTaskManager taskManager,
                                RAGObservabilityService ragObservabilityService) {
        this.webChatService = webChatService;
        this.taskManager = taskManager;
        this.ragObservabilityService = ragObservabilityService;
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
        traceStreamDispatch(
                estimateChunkCount(text),
                0,
                () -> chunkedTextStreamer.stream(emitter, text, () -> taskManager.isCancelled(taskId))
        );
    }

    public <T> T streamObservedAnswer(StreamEventEmitter emitter,
                                      String taskId,
                                      Function<java.util.function.Consumer<String>, T> producer) {
        String traceId = io.github.imzmq.interview.core.trace.RAGTraceContext.getTraceId();
        String parentNodeId = resolveDispatchParentNodeId(traceId);
        AtomicInteger chunkCounter = new AtomicInteger();
        AtomicLong dispatchNanos = new AtomicLong();
        AtomicInteger nodeStarted = new AtomicInteger(0);
        String nodeId = java.util.UUID.randomUUID().toString();
        try {
            T result = producer.apply(token -> {
                if (taskManager.isCancelled(taskId)) {
                    return;
                }
                if (traceId != null && !traceId.isBlank()
                        && parentNodeId != null && !parentNodeId.isBlank()
                        && nodeStarted.compareAndSet(0, 1)) {
                    ragObservabilityService.startNode(
                            traceId,
                            nodeId,
                            parentNodeId,
                            TraceNodeDefinitions.STREAM_DISPATCH.nodeType(),
                            TraceNodeDefinitions.STREAM_DISPATCH.nodeName()
                    );
                }
                long emitStart = System.nanoTime();
                emitter.emit(InterviewStreamEventType.MESSAGE.value(), Map.of(
                        "channel", "answer",
                        "delta", token
                ));
                dispatchNanos.addAndGet(System.nanoTime() - emitStart);
                chunkCounter.incrementAndGet();
            });
            finishObservedAnswer(traceId, nodeId, nodeStarted.get() == 1, chunkCounter.get(), dispatchNanos.get(), null);
            return result;
        } catch (RuntimeException ex) {
            finishObservedAnswer(traceId, nodeId, nodeStarted.get() == 1, chunkCounter.get(), dispatchNanos.get(), ex);
            throw ex;
        }
    }

    public void sendImageEvents(StreamEventEmitter emitter, List<?> images, String taskId) {
        traceStreamDispatch(
                0,
                images == null ? 0 : images.size(),
                () -> {
                    if (images == null || images.isEmpty()) {
                        return;
                    }
                    for (Object image : images) {
                        if (taskManager.isCancelled(taskId)) {
                            return;
                        }
                        emitter.emit(InterviewStreamEventType.IMAGE.value(), image);
                    }
                }
        );
    }

    private void traceStreamDispatch(int chunkCount, int imageEventCount, Runnable action) {
        traceStreamDispatchResult(chunkCount, imageEventCount, actualChunkCount -> {
            action.run();
            return null;
        });
    }

    private <T> T traceStreamDispatchResult(int chunkCount,
                                            int imageEventCount,
                                            Function<AtomicInteger, T> action) {
        String traceId = io.github.imzmq.interview.core.trace.RAGTraceContext.getTraceId();
        if (traceId == null || traceId.isBlank()) {
            return action.apply(null);
        }
        String parentNodeId = resolveDispatchParentNodeId(traceId);
        if (parentNodeId == null || parentNodeId.isBlank()) {
            return action.apply(null);
        }
        String nodeId = java.util.UUID.randomUUID().toString();
        ragObservabilityService.startNode(
                traceId,
                nodeId,
                parentNodeId,
                TraceNodeDefinitions.STREAM_DISPATCH.nodeType(),
                TraceNodeDefinitions.STREAM_DISPATCH.nodeName()
        );
        long start = System.currentTimeMillis();
        AtomicInteger actualChunkCount = new AtomicInteger(chunkCount);
        try {
            T result = action.apply(actualChunkCount);
            long completionMs = System.currentTimeMillis() - start;
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("status", "COMPLETED");
            output.put("completionMs", completionMs);
            if (actualChunkCount.get() > 0) {
                output.put("chunkCount", actualChunkCount.get());
            }
            if (imageEventCount > 0) {
                output.put("imageEventCount", imageEventCount);
            }
            ragObservabilityService.endNode(
                    traceId,
                    nodeId,
                    "",
                    output.toString(),
                    null,
                    null,
                    new RAGObservabilityService.NodeDetails(
                            1,
                            null,
                            null,
                            null,
                            null,
                            List.of(),
                            null,
                            imageEventCount > 0 ? imageEventCount : null,
                            null,
                            null,
                            null,
                            completionMs
                    )
            );
            return result;
        } catch (RuntimeException ex) {
            long completionMs = System.currentTimeMillis() - start;
            ragObservabilityService.endNode(
                    traceId,
                    nodeId,
                    "",
                    Map.of("status", "FAILED", "completionMs", completionMs).toString(),
                    ex.getMessage(),
                    null,
                    new RAGObservabilityService.NodeDetails(
                            1,
                            null,
                            null,
                            null,
                            null,
                            List.of(),
                            null,
                            imageEventCount > 0 ? imageEventCount : null,
                            null,
                            null,
                            null,
                            completionMs
                    )
            );
            throw ex;
        }
    }

    private String resolveDispatchParentNodeId(String traceId) {
        String parentNodeId = io.github.imzmq.interview.core.trace.RAGTraceContext.getCurrentNodeId();
        if (parentNodeId == null || parentNodeId.isBlank()) {
            parentNodeId = ragObservabilityService.getActiveRootNodeId(traceId);
        }
        return parentNodeId;
    }

    private void finishObservedAnswer(String traceId,
                                      String nodeId,
                                      boolean started,
                                      int chunkCount,
                                      long dispatchNanos,
                                      RuntimeException error) {
        if (!started || traceId == null || traceId.isBlank()) {
            return;
        }
        long completionMs = Math.max(0L, dispatchNanos / 1_000_000L);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", error == null ? "COMPLETED" : "FAILED");
        output.put("completionMs", completionMs);
        output.put("chunkCount", chunkCount);
        ragObservabilityService.endNode(
                traceId,
                nodeId,
                "",
                output.toString(),
                error == null ? null : error.getMessage(),
                null,
                new RAGObservabilityService.NodeDetails(
                        1,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        completionMs
                )
        );
    }

    private int estimateChunkCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int chunkSize = 32;
        return Math.max(1, (text.length() + chunkSize - 1) / chunkSize);
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








